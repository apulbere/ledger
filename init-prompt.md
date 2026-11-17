Scaffold a greenfield Gradle monorepo for a double-entry ledger. OpenSpec is already initialized (openspec/ exists). Scaffold directly; do NOT create OpenSpec changes. No business logic and no domain code: features come later via OpenSpec. Keep everything minimal; do not generate speculative docs or code.

Prerequisites: JDK 25 and a running Docker runtime. If either is missing, ask before installing.

## Stack
Java 25 (LTS) via Gradle toolchain · Spring Boot 4.x (latest stable) · Gradle Kotlin DSL (latest stable wrapper that supports Java 25) · PostgreSQL · Liquibase · Kafka (KRaft) · Debezium (Kafka Connect) · Avro + Confluent Schema Registry · JUnit Jupiter (version from the Boot BOM) · Testcontainers · Docker Compose.
Verify versions are mutually compatible before pinning (including Debezium support for the chosen Postgres major); tell me what you picked.

## Architecture (target, for context only; do not implement)
- journal (write side): accepts posting requests over REST and appends transactions to an append-only transaction log table in Postgres. journal never talks to Kafka.
- CDC: Debezium streams INSERTs from journal's transaction log to Kafka (unwrap SMT, Avro via Schema Registry).
- query (read side): consumes those events, keeps balance projections in its own schema, exposes REST APIs (balance inquiry first).
- Each service owns its own DB schema and DB user; no cross-service table access.

## Layout: composite build, each service is its own Gradle build
ledger/
├── settings.gradle.kts         # root: includeBuild build-logic and each service
├── build.gradle.kts            # root build/clean tasks that run the service builds
├── gradle.properties           # org.gradle.caching=true, configuration-cache=true, parallel=true
├── gradle/libs.versions.toml   # single shared version catalog
├── build-logic/                # convention plugins: ledger.java (Java 25, JUnit Jupiter),
│                               # ledger.spring-app (Boot, Actuator, Liquibase, Testcontainers),
│                               # ledger.avro (cacheable codegen task in build-logic calling avro-compiler; no third-party Avro plugin)
├── docker-compose.yml
├── docker/                     # postgres init script, kafka-connect Dockerfile
├── docs/README.md              # living docs index only (see Docs)
├── services/journal/           # own settings.gradle.kts and gradlew
│     journal-app/              #   Spring Boot app + Dockerfile
│                               #   Liquibase: db/changelog/db.changelog-master.yaml, first changeset creates schema "journal" only
│     journal-events/           #   Avro module, wired with ledger.avro; contract NOT defined yet:
│                               #   src/main/avro/TransactionPosted.avsc as a placeholder record with an empty "fields" array
│                               #   and a doc "TBD via OpenSpec".
└── services/query/             # own settings.gradle.kts and gradlew
      query-app/                #   Spring Boot app + Dockerfile; depends on journal-events, NEVER journal-app
                                #   Liquibase: first changeset creates schema "query" only
Service builds reuse the root version catalog and build-logic. query resolves journal-events via Maven coordinates, substituted locally by includeBuild("../journal") in query's own settings, so query also builds standalone.
Liquibase tracking tables cannot live in the service schema (the changelog creates it): put them in public with service-prefixed names (journal_databasechangelog, ...).

## Minimal code
- Each app: main class, Actuator health, one context-loads test with Testcontainers Postgres. Nothing else.

## Dev infra: everything starts with ONE command: docker compose up --build
- Dockerfile per app: multi-stage, Gradle build, Java 25 JRE runtime image. Build context = repo root so the composite build resolves. Add a .dockerignore.
- docker-compose.yml services, all with healthchecks (JRE and cp-schema-registry images have no curl; use bash /dev/tcp):
  - postgres: wal_level=logical; init script creates users journal_user / query_user and a replication-capable user for Debezium, and grants CREATE on the database and on schema public to journal_user / query_user
  - kafka: KRaft, single node
  - schema-registry
  - kafka-connect: Debezium image, Avro converters pointing to schema-registry (no connectors registered yet). Debezium images do not ship the Confluent Avro converter: build a small image in docker/ that adds it.
  - journal-app, query-app: depend_on postgres/kafka/schema-registry healthy
- Host ports: journal 8080, query 8081, postgres 5432, kafka 9092, schema-registry 8085, kafka-connect 8083.
- `docker compose down -v` resets everything.
- .gitignore, .gitattributes (gradlew LF, *.bat CRLF), .editorconfig, short README.md (build one service vs all, `docker compose up --build`).
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
- cd services/journal && ./gradlew build passes; same for services/query
- docker compose down -v && docker compose up --build: all services healthy
Show the final tree, the versions you chose, and anything you were unsure about. Then STOP.