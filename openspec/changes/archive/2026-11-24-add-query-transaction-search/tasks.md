# Tasks

## 1. Dependencies and configuration

- [x] 1.1 Add to `services/query/query-app/build.gradle.kts`: `spring-boot-starter-jdbc`, Spring for Apache Kafka (Boot-managed starter); test: `spring-boot-starter-webmvc-test`, `testcontainers-kafka`, `libs.swagger.request.validator`; declare `src/main/openapi/openapi.yaml` as a test task input (as in `journal-app`); verify `cd services/query && ./gradlew build` passes
- [x] 1.2 Add Kafka and Schema Registry settings to `query-app` `application.yaml` (bootstrap servers and registry URL from env with host-port defaults 9092/8085, group id `query` overridable, `auto-offset-reset: earliest`, byte-array key/value deserializers, record ack mode, missing topics not fatal); add a shared Testcontainers configuration (Postgres 18, `apache/kafka:4.3.2`, `cp-schema-registry:8.3.3` on one network) and switch `QueryApplicationTests` to it; verify the context loads with no topics present

## 2. Projection tables

- [x] 2.1 Add Liquibase changesets (one SQL file each, listed in `db.changelog-master.yaml`, with rollback) for `query.transaction`, `query.account_transaction` (PK `(account_id, transaction_id)`, FK to `transaction`, `currencies char(3)[]`, index `(account_id, occurred_at DESC, transaction_id DESC)`) and `query.balance_version` (PK `(account_id, currency, version)`, index on `last_transaction_id`), exactly as the design ER diagram; verify with a schema test asserting tables, keys and the index exist

## 3. Event decoding

- [x] 3.1 Implement wire-format decoding in `query-app`: magic byte check, schema id, writer schema fetched from Schema Registry by id and cached, Avro resolution into the generated `journal-events` records (`TransactionKey`, `TransactionPosted`, `BalanceKey`, `BalanceChanged`); verify with unit tests for a valid record, wrong magic byte, unknown schema id and truncated body (each failure throws, none returns null)
- [x] 3.2 Add a test producer helper that registers the `journal-events` schemas under subjects `ledger.journal.transaction-key/-value` and `ledger.journal.balance-key/-value` and writes wire-format records; verify a round trip through 3.1 against the Testcontainers Schema Registry

## 4. Balance ingestion (`query/account-balances`)

- [x] 4.1 Implement the balance listener: decode, then insert the balance version ignoring an already held one, in one DB transaction (design decisions 0 and 6); configure the container error handler for infinite retry with exponential backoff capped at 1 minute and no non-retryable exceptions (design decision 5)
- [x] 4.2 Integration tests covering every `query/account-balances` scenario: versions 1-2-3 are all held with current version 3, FX `T1` versions are exactly EUR 18 and USD 4 by `last_transaction_id`, redelivered version stays a single unchanged row, late version 2 after 1 and 3 is held while current stays 3, every version published before startup is ingested (fresh group from earliest), an undecodable record blocks later versions on the topic; verify `cd services/query && ./gradlew test` passes

## 5. Posting ingestion (`query/transaction-search`, ingestion requirements)

- [x] 5.1 Implement the posting listener: decode, parse `entries`, insert the posting `ON CONFLICT (id) DO NOTHING` and, only when inserted, one `account_transaction` row per distinct account with its currencies, in one DB transaction; timestamps parsed to instants
- [x] 5.2 Integration tests: a posting touching `cus_A` and `int_SETTLEMENT_VISA` yields one posting row and two index rows; a hold posting with two `cus_A` entries yields one index row; FX posting yields `cus_A` currencies `{EUR,USD}`; a redelivered posting changes nothing; postings published before startup are ingested; an undecodable record blocks later postings while earlier ones stay stored; verify `./gradlew test` passes

## 6. Search REST API (`query/transaction-search`, search requirements)

- [x] 6.1 Write `services/query/query-app/src/main/openapi/openapi.yaml` first: server `/query`, `GET /accounts/{accountId}/transactions` with `currency`, `occurredFrom`, `occurredTo`, `limit` (1–100, default 20), `cursor`; `200` `TransactionPage {items: Posting[], nextCursor: string|null}` with `Posting`, `Entry`, `BalanceVersion`, `AccountId`, `Currency` mirroring the journal's document; `400` `BadRequestProblem` with `code: INVALID_PARAMETER` and `parameter`; at least one example response; verify it parses with the Swagger request validator
- [x] 6.2 Implement parameter validation, cursor encode/decode (base64url of `occurredAt` and id), the keyset query (`limit + 1`, `occurred_at DESC, transaction_id DESC`, currency `@>` filter, `[from, to)` range) and problem rendering, plain JDBC
- [x] 6.3 Add an `OpenApiContract` helper (same rules as `journal`'s) and MockMvc tests with seeded projection rows, every request checked against the document, covering: customer and internal account search, one item per posting, unknown account empty with `nextCursor` null, ordering by `occurredAt` then id, backdated posting order, currency filter (FX matches both; another account's currency does not match), range bounds inclusive-exclusive, empty range → `occurredTo`, paging 45 → 20/20/5 with no repeats, default limit 20, exactly-full page has `nextCursor` null, and `400 INVALID_PARAMETER` for `acct_42`, `currency=eur`, bad timestamp, `limit=0`/`101` and `cursor=not-a-cursor`; verify `./gradlew test` passes
- [x] 6.4 Add an "item equals the recorded posting" test: take a journal `201` response body fixture (with `referenceId`, `metadata`, four FX entries and two versions), publish it as a `TransactionPosted` event, search it, and assert the item equals the fixture as JSON with timestamps compared as instants

## 7. Compose, gateway and README

- [x] 7.1 In `docker-compose.yml` give `query-app` `KAFKA_BOOTSTRAP_SERVERS=kafka:29092` and `SCHEMA_REGISTRY_URL=http://schema-registry:8081`; mount `query`'s `openapi.yaml` into `swagger-ui` and list it in `URLS`; add a `/query/` location to `docker/gateway/default.conf`; update `README.md` (query search endpoint through the gateway, consumer group reset note for rebuilding projections) and verify the README commands as written

## 8. End-to-end check and docs

- [x] 8.1 Run `./gradlew build` at the repo root, then `docker compose down -v && docker compose up --build`; post the journal OpenAPI examples (top-up, card authorization, clearing, FX) through the gateway; verify within seconds that `GET localhost:8090/query/accounts/cus_01jcz8k2m4f7r9t3vbx5w6y8za/transactions` returns them newest `occurredAt` first, `?currency=USD` returns only the FX posting, `/accounts/int_SETTLEMENT_VISA/transactions` returns the clearing, `query.balance_version` holds every EUR and USD version, the FX posting's two linked by `last_transaction_id`, and "Try it out" works in Swagger UI for `query`; restart `query-app` and verify nothing is duplicated
- [x] 8.2 Update living docs from the implemented code: `docs/architecture/containers.md` (query consumes both topics and Schema Registry; gateway in front of journal and query), create `services/query/docs/data-model.md` (ER of the three tables, including the balance version history, with the non-obvious comments), `services/query/docs/ingestion.md` (sequence from topics to projections: idempotent writes, offset after commit, never skip) and `services/query/docs/transaction-search.md` (read path, keyset cursor note), each linking the specs, the OpenAPI document, the Avro contract and `data-model.md`; add them to the `docs/README.md` index; check the container diagram is still accurate and drop anything a contract or spec already states

## Workflow follow-up

- Archive the change after review; carry decisions that still hold (one index row per account and posting, order by `occurredAt`, balance history with current = highest version, never skip, offset after DB commit) into the query component docs as diagram notes.
