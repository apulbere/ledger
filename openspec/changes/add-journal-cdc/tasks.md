# Tasks

## 1. Avro contract

- [x] 1.1 Replace the `TransactionPosted.avsc` placeholder in `services/journal/journal-events/src/main/avro/` with the full record (namespace `ledger.journal.events`, one field per `journal.transaction` column, types per design decision 4, `doc` describing the JSON shape of `entries`, `versions` and `metadata`) and add `TransactionKey.avsc`, `BalanceChanged.avsc`, `BalanceKey.avsc`; verify `cd services/query && ./gradlew build` compiles the generated classes

## 2. Database prerequisites

- [x] 2.1 Add Liquibase changeset `create-publication-journal-cdc.sql` creating publication `journal_cdc` for `journal.transaction` and `journal.balance` (with rollback), include it in `db.changelog-master.yaml`; extend `SchemaTest` to assert the publication exists and covers exactly those tables
- [x] 2.2 In `docker/postgres/journal-init.sql`, grant `debezium_user` default `USAGE` on schemas and `SELECT` on tables created by `journal_user`; in `docker-compose.yml` set `max_slot_wal_keep_size` on `journal-postgres`; verify after `docker compose down -v && docker compose up --build` that `debezium_user` can `SELECT` from `journal.transaction`

## 3. Connector and compose

- [x] 3.1 Add `docker/kafka-connect/connectors/journal-cdc.json`: Postgres connector, `pgoutput`, slot and publication `journal_cdc`, autocreate disabled, `table.include.list` for both tables, `topic.prefix=ledger`, `snapshot.mode=initial`, unwrap SMT dropping tombstones, per-topic `SetSchemaMetadata` renames for key and value, `topic.creation.default.*` (1 partition, delete policy, infinite retention)
- [x] 3.2 Add the one-shot `connector-init` service to `docker-compose.yml` (pinned curl image, depends on healthy `kafka-connect` and `journal-app`, idempotent `PUT /connectors/journal-cdc/config`); verify with `docker compose up --build` that `GET :8083/connectors/journal-cdc/status` is `RUNNING` and that re-running the service is a no-op
- [x] 3.3 Update `README.md` (run section: connector registered automatically, topics, existing volumes need `down -v` for the grants) and verify the commands as written

## 4. CDC integration test

- [x] 4.1 Add test dependencies to `journal-app` (Testcontainers Kafka module, Kafka clients) and a CDC test configuration: Postgres 18 with database `journal`, `wal_level=logical` and the real `journal-init.sql`; Kafka; Schema Registry; Connect built from `docker/kafka-connect/Dockerfile`; shared network; journal booted against that Postgres; connector registered from `journal-cdc.json` with hostnames overridden. Verify the connector reaches `RUNNING` in the test
- [x] 4.2 Add a test helper that consumes a topic and decodes Confluent wire format using the writer schema fetched from Schema Registry
- [x] 4.3 Cover the spec scenarios in one `ChangeEventsIntegrationTest`: posting event content and key, `entries` equal to the 201 response, FX posting gives two balance events with full totals and `last_transaction_id`, internal-only posting gives no balance event, rejected posting gives no event, three postings arrive as versions 1-2-3 in order, postings recorded before registration are snapshotted, postings during a paused connector arrive after resume, and topic configs show delete policy with infinite retention; verify `cd services/journal && ./gradlew test` passes
- [x] 4.4 Add the contract assertion: each registered key and value writer schema has the same Avro parsing canonical form as its `journal-events` schema; verify by temporarily renaming a field in one `.avsc` and seeing the test fail
- [x] 4.5 Verify "posting does not depend on publishing": the existing posting tests run without Kafka and stay green, and manually with `docker compose stop kafka` a `curl` posting still returns `201`, then its events appear after `docker compose start kafka`

## 5. End-to-end check and docs

- [x] 5.1 Run `./gradlew build` at the repo root and `docker compose down -v && docker compose up --build`, post an FX posting through the gateway, and read both topics with `kafka-avro-console-consumer` (or the Schema Registry-aware console consumer in the schema-registry container) to see one posting event and two balance events
- [x] 5.2 Update living docs from the implemented code: `docs/architecture/containers.md` (Kafka Connect reads journal Postgres, writes the two topics), create `services/journal/docs/change-events.md` (sequence from commit to topics, linking the spec, the Avro contract, the connector config and `data-model.md`), add it to the `docs/README.md` index; check the container diagram is still accurate and drop anything a contract or spec already states

## Workflow follow-up

- Archive the change after review; carry design decisions that still hold (publication owned by journal, canonical-form contract check, at-least-once via the slot) into `change-events.md` as diagram notes.
