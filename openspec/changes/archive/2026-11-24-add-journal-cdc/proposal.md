# Proposal

## Why

`journal` records postings and maintains customer balances, but nothing leaves its database: the read side (`query`) has no way to learn about them. The tables were shaped from the start so that one posting is one change event and one balance change is one self-contained event; this change turns that on, so a later `query` change can build balance inquiry and transaction search on top of a stable event contract.

## What Changes

- A Debezium Postgres connector streams committed inserts of `journal.transaction` and inserts and updates of `journal.balance` to two Kafka topics, `ledger.journal.transaction` and `ledger.journal.balance`, as Avro registered in Schema Registry.
- Each posting becomes one event keyed by posting id, carrying the full recorded posting (envelope, entries, versions). Each customer balance change becomes one event keyed by account and currency, carrying the four debit and credit totals, `version` and `last_transaction_id` after the posting.
- The event contract is hand-written in `journal-events` (replacing the `TransactionPosted` placeholder, adding `BalanceChanged` and the two key records). A test proves the schemas the connector registers match it.
- Rows recorded before the connector first starts are streamed too (initial snapshot). Delivery is at least once; events are retained indefinitely so a consumer can rebuild from the beginning.
- `docker compose up --build` registers the connector automatically. `journal` gains a migration that creates the CDC publication; the Debezium database user gets read access through the Postgres init script. `journal`'s application code stays unaware of Kafka, and posting never waits on CDC.
- Out of scope: any `query` code (consumers, projections, balance and transaction search REST APIs) — a separate change. No change to the `journal` REST contract or to posting and balance behaviour.

## Capabilities

### New Capabilities
- `journal/change-events`: the events `journal` publishes for recorded postings and customer balance changes — topics, keys, payload, ordering, delivery guarantees, retention and the Avro contract downstream consumers rely on.

### Modified Capabilities
<!-- none: posting and balance behaviour are unchanged -->

## Impact

- `services/journal/journal-events`: Avro contract (`TransactionPosted`, `BalanceChanged`, `TransactionKey`, `BalanceKey`).
- `services/journal/journal-app`: one Liquibase changeset (publication); CDC integration test with Kafka, Schema Registry and Kafka Connect containers. No production code change.
- `docker/`: connector configuration, Debezium user grants in `journal-init.sql`; `docker-compose.yml` gains a one-shot connector registration service.
- `query`: none in this change; it already depends on `journal-events` and will consume these topics later.
- New test dependencies: Testcontainers Kafka module, Kafka clients.

## Docs impact

- System: update `docs/architecture/containers.md` — Kafka Connect reads the journal Postgres and writes the two journal topics.
- Component: create `services/journal/docs/change-events.md` (sequence from commit to topics), and add it to the `docs/README.md` index; `data-model.md` unchanged (no table change).
