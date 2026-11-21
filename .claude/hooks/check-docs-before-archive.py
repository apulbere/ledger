"""PreToolUse hook: block archiving an OpenSpec change whose living docs were not updated.

Triggers on `openspec archive <name>` and on `mv openspec/changes/<name> .../archive/...`
(what the opsx archive skill runs). The proposal's "Docs impact" section must have a
"- System:" and a "- Component:" bullet. Every doc path it names must exist (unless marked
for deletion), be changed since the proposal was first committed (or in the working tree),
and be linked from docs/README.md. If the change touched the deployment (docker-compose.yml),
the container diagram must have changed too, whatever the Docs impact says.
Exit 2 blocks the tool call and feeds stderr back to Claude.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

DOC_PATH = re.compile(r"`((?:docs|services/[^/`]+/docs)/[^`]+\.md)`")
LEVEL = re.compile(r"^\s*[-*]\s*\**(system|component)\**\s*:", re.I | re.M)
DEPLOYMENT_FILES = ("docker-compose.yml",)
CONTAINERS_DOC = "docs/architecture/containers.md"


def change_name(command):
    m = re.search(r"\bopenspec\s+archive\b((?:\s+\S+)*)", command)
    if m:
        args = [a for a in m.group(1).split() if not a.startswith("-")]
        return args[0].strip("'\"") if args else None
    if re.search(r"\bmv\b", command) and "/archive/" in command:
        m = re.search(r"openspec/changes/([^/\s\"']+)", command)
        if m and m.group(1) != "archive":
            return m.group(1)
    return None


def docs_impact(proposal):
    m = re.search(r"^## Docs impact\s*\n(.*?)(?=^## |\Z)", proposal, re.S | re.M)
    return m.group(1).strip() if m else None


def git(root, *args):
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True).stdout


def changed_files(root, proposal_rel):
    added = git(root, "log", "--diff-filter=A", "--format=%H", "--", proposal_rel).split()
    base = added[-1] if added else "HEAD"
    files = set(git(root, "diff", "--name-only", base, "--").split())
    files |= set(git(root, "ls-files", "--others", "--exclude-standard").split())
    return files


def main():
    payload = json.load(sys.stdin)
    name = change_name(payload.get("tool_input", {}).get("command", ""))
    if not name:
        return 0

    root = Path(payload.get("cwd") or ".").resolve()
    top = git(root, "rev-parse", "--show-toplevel").strip()
    root = Path(top) if top else root
    proposal_rel = f"openspec/changes/{name}/proposal.md"
    proposal_path = root / proposal_rel
    if not proposal_path.exists():
        return 0

    impact = docs_impact(proposal_path.read_text())
    if impact is None:
        print(f"{proposal_rel} has no '## Docs impact' section. Add one (or 'none' with a reason) before archiving.", file=sys.stderr)
        return 2
    changed = changed_files(root, proposal_rel)
    index = (root / "docs/README.md").read_text() if (root / "docs/README.md").exists() else ""
    problems = []
    levels = {m.lower() for m in LEVEL.findall(impact)}
    for level in ("System", "Component"):
        if level.lower() not in levels:
            problems.append(f"Docs impact has no '- {level}:' bullet (list docs, or 'none' with a reason)")
    deployment = [f for f in DEPLOYMENT_FILES if f in changed]
    if deployment and CONTAINERS_DOC not in changed:
        problems.append(f"{', '.join(deployment)} changed but {CONTAINERS_DOC} did not")
    for line in impact.splitlines():
        deleting = re.search(r"\b(delete|remove)\b", line, re.I)
        for doc in DOC_PATH.findall(line):
            exists = (root / doc).exists()
            if deleting:
                if exists:
                    problems.append(f"{doc} is marked for deletion but still exists")
                continue
            if not exists:
                problems.append(f"{doc} does not exist")
            elif doc not in changed:
                problems.append(f"{doc} was not changed by this change")
            elif doc != "docs/README.md" and Path(doc).name not in index:
                problems.append(f"{doc} is not linked from docs/README.md")

    if problems:
        print(
            f"Living docs for '{name}' are not up to date (per its Docs impact):\n- "
            + "\n- ".join(problems)
            + "\nUpdate them to match the implemented code before archiving.",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
