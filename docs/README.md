# Docs

Living docs: they describe the **current** system, not plans or history.

- System-level diagrams (C4 context/container, cross-service flows) go in `docs/architecture/`.
- Component-level diagrams (C4 component, ER, sequence, state) go in `services/<service>/docs/`.
- Mermaid only. Minimal: a diagram and a line of links, with no text that describes the code.
- Required behaviour lives in the OpenSpec main specs ([`openspec/specs/`](../openspec/specs/)); docs link to them instead of restating them.
- Files are created by the first OpenSpec change that needs them. No placeholders.

## Index

- [system: containers](architecture/containers.md): container diagram of the deployed system.
- [journal: data model](../services/journal/docs/data-model.md): ER diagram of the posting log and customer balances.
- [journal: posting](../services/journal/docs/posting.md): sequence diagram of the write path.
- [journal: change events](../services/journal/docs/change-events.md): sequence diagram from commit to the Kafka topics.
