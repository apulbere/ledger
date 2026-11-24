# Proposal

## Why

`journal` now publishes every recorded posting and every customer balance change to Kafka, but nothing consumes them: `query` is still an empty scaffold, and the only way to see an account's postings is to read the journal's database. Upstream systems and support need to list the postings that touched an account, without loading the write side. This change makes `query` the read side it was meant to be: it builds its own projections from the change events and serves the first read API, transaction search by account.

## What Changes

- `query` consumes `ledger.journal.transaction` and `ledger.journal.balance` (Avro, Schema Registry) from the earliest offset and keeps two projections in its own `query` schema:
  - every recorded posting, indexed by every account id its entries touch (customer and internal accounts);
  - the current state of every customer balance per account and currency (latest `version` wins). Stored only; no balance endpoint in this change.
- Ingestion is idempotent and order-tolerant: a redelivered posting or balance event changes nothing, and an older balance version never overwrites a newer one. A malformed event stops consumption of its partition (retried, never skipped) instead of silently losing a posting.
- `query` exposes `GET /accounts/{accountId}/transactions`: postings that touched the account, newest `occurredAt` first, optionally filtered by `currency` and an `occurredAt` range, with cursor-based paging (`limit` ≤ 100). Each item has the same shape as the journal's recorded posting. Results are eventually consistent with the journal.
- `query` gets a hand-written OpenAPI contract (`query-app/src/main/openapi/openapi.yaml`), validated against real traffic in tests, and is published through the gateway (`/query/...`) and its Swagger UI.
- `docker-compose.yml`: `query-app` gets Kafka and Schema Registry settings; gateway and Swagger UI gain the `query` service.
- Out of scope: balance inquiry REST, search by anything other than account id (e.g. `referenceId`), balance history, changes to the journal or its event contract.

## Capabilities

### New Capabilities
- `query/transaction-search`: ingesting recorded postings from the journal's change events and searching them by account id over REST — filters, ordering, paging, response shape, consistency and duplicate handling.
- `query/account-balances`: the read side's projection of current customer balances per account and currency, built from balance change events — idempotent, version-ordered. Later balance inquiry will extend this capability.

### Modified Capabilities
<!-- none: the journal's posting, balance and change-event behaviour is unchanged -->

## Impact

- `services/query/query-app`: Kafka consumers, Avro decoding of the Confluent wire format with the `journal-events` classes, Liquibase changesets for the projection tables, search REST endpoint, problem handling, OpenAPI document and tests (Testcontainers Postgres, Kafka, Schema Registry).
- New dependencies in `query-app`: Spring for Apache Kafka (Boot-managed); test: Testcontainers Kafka, Swagger request validator (already in the version catalog).
- `docker-compose.yml`, `docker/gateway/default.conf`: query wiring and gateway route; README port/endpoint notes.
- `journal`, `journal-events`, the connector: no change.

## Docs impact

- System: update `docs/architecture/containers.md` — `query` now consumes both journal topics from Kafka (and Schema Registry) and is reached through the gateway.
- Component: create `services/query/docs/data-model.md` (ER of the posting, account index and balance projections), `services/query/docs/ingestion.md` (sequence from topics to projections) and `services/query/docs/transaction-search.md` (search read path), and add them to the `docs/README.md` index.
