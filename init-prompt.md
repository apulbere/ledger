No. That was handling a problem that only exists because the connector would be registered before its table exists. The leaner fix is to leave connector registration out of the scaffold entirely and add it in the CDC change, when there's a table to stream. That removes `connect-init` and the premature `log-connector.json`.

```text
Scaffold a greenfield Gradle monorepo for a double-entry ledger. OpenSpec is already initialized (openspec/ exists). Scaffold directly; do NOT create OpenSpec changes. No business logic and no domain code: features come later via OpenSpec. Keep everything minimal; do not generate speculative docs or code.

## Stack
Java 25 (LTS) via Gradle toolchain · Spring Boot 4.x (latest stable) · Gradle Kotlin DSL (latest stable wrapper that supports Java 25) · PostgreSQL · Liquibase · Kafka (KRaft) · Debezium (Kafka Connect) · Avro + Confluent Schema Registry · JUnit 5 · Testcontainers · Docker Compose.
Verify versions are mutually compatible before pinning; tell me what you picked.

## Architecture (target, for context only; do not implement)
- journal (write side): accepts posting requests over REST and appends transactions to an append-only transaction log table in Postgres. journal never talks to Kafka.
- CDC: Debezium streams INSERTs from journal's transaction log to Kafka (unwrap SMT, Avro via Schema Registry).
- query (read side): consumes those events, keeps balance projections in its own schema, exposes REST APIs (balance inquiry first).
- Each service owns its own DB schema and DB user; no cross-service table access.

## Layout: composite build, each service is its own Gradle build
ledger/
├── settings.gradle.kts         # root: includeBuild build-logic and each service
├── gradle.properties           # org.gradle.caching=true, configuration-cache=true, parallel=true
├── gradle/libs.versions.toml   # single shared version catalog
├── build-logic/                # convention plugins: ledger.java (Java 25, JUnit 5),
│                               # ledger.spring-app (Boot, Actuator, Liquibase, Testcontainers), ledger.avro (cacheable codegen)
├── docker-compose.yml
├── docker/                     # postgres init script
├── docs/README.md              # living docs index only (see Docs)
├── services/journal/           # own settings.gradle.kts
│     journal-app/              #   Spring Boot app + Dockerfile
│                               #   Liquibase: db/changelog/db.changelog-master.yaml, first changeset creates schema "journal" only
│     journal-events/           #   Avro module, wired with ledger.avro; contract NOT defined yet:
│                               #   src/main/avro/TransactionPosted.avsc as a placeholder record with an empty "fields" array
│                               #   and a doc "TBD via OpenSpec". If the codegen plugin rejects an empty record,
│                               #   leave src/main/avro empty with a .gitkeep instead and tell me.
└── services/query/             # own settings.gradle.kts
      query-app/                #   Spring Boot app + Dockerfile; depends on journal-events, NEVER journal-app
                                #   Liquibase: first changeset creates schema "query" only
Service builds reuse the root version catalog and build-logic. query resolves journal-events via Maven coordinates, substituted locally by includeBuild.

## Minimal code
- Each app: main class, Actuator health, one context-loads test with Testcontainers Postgres. Nothing else.

## Dev infra: everything starts with ONE command: docker compose up --build
- Dockerfile per app: multi-stage, Gradle build, Java 25 JRE runtime image. Build context = repo root so the composite build resolves.
- docker-compose.yml services, all with healthchecks:
  - postgres: wal_level=logical; init script creates users journal_user / query_user and a replication-capable user for Debezium
  - kafka: KRaft, single node
  - schema-registry
  - kafka-connect: Debezium image, Avro converters pointing to schema-registry (no connectors registered yet)
  - journal-app, query-app: depend_on postgres/kafka/schema-registry healthy
- Host ports: journal 8080, query 8081, postgres 5432, kafka 9092, schema-registry 8085, kafka-connect 8083.
- `docker compose down -v` resets everything.
- .gitignore, .editorconfig, short README.md (build one service vs all, `docker compose up --build`).
- .github/workflows/ci.yml: Gradle build with caching.

## Docs: living docs, created lazily
- Create ONLY docs/README.md: a few lines stating the convention below, plus an empty index.
- Convention (put it in docs/README.md):
  - System-level diagrams go in docs/architecture/ (C4 context/container, cross-service flows).
  - Component-level diagrams go in services/<service>/docs/ (C4 component, ER, sequence, state).
  - Mermaid only. Docs describe the CURRENT system. Files are created by the first OpenSpec change that needs them; no placeholders.

## openspec/config.yaml
context:
- Living docs: docs/architecture/ (system) and services/<service>/docs/ (component); Mermaid; describe the current system.
rules (use the rules format supported by the installed OpenSpec version; tell me if it differs):
- proposal: include a "Docs impact" section listing living docs to create or update, or "none".
- tasks: if Docs impact is not "none", add a final task to merge the relevant design.md diagrams into those living docs and update the index in docs/README.md.

## Verify
- ./gradlew build at root passes
- cd services/journal && ./gradlew build passes
- docker compose up --build: all services healthy
Show the final tree, the versions you chose, and anything you were unsure about. Then STOP.
```

The connector config and its registration move into `add-transaction-log-cdc`.