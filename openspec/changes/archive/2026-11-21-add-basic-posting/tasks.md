# Tasks

## 1. Setup

- [x] 1.1 Add `spring-boot-starter-jdbc` to `services/journal/journal-app/build.gradle.kts`, then confirm `cd services/journal && ./gradlew build` still passes and the context-loads test is green
- [x] 1.2 Move the build to Java 27 (version catalog, Dockerfiles, CI, README) and use the JDK's native UUIDv7 (D7). Verify the build passes and a unit test shows `UUID.ofEpochMillis` produces version-7 UUIDs

## 2. Schema

- [x] 2.1 Add a Liquibase changeset creating `journal.transaction` (D1, D6, D7): `id uuid PRIMARY KEY` (no default, set by the app), unique `idempotency_key`, `reference_id` (nullable, no index), `occurred_at`, `posted_at DEFAULT now()`, `description`, `metadata jsonb`, `entries jsonb NOT NULL`, `versions jsonb NOT NULL`. Verify the app boots against Testcontainers and the table exists
- [x] 2.2 Add a Liquibase changeset creating `journal.balance` (D2) with PK `(account_id, currency)`, `available`, `held`, `version` as `bigint NOT NULL`, and `last_transaction_id uuid NOT NULL`. Verify with a schema test
- [x] 2.3 Add the append-only trigger on `journal.transaction` (D8). Verify with a repository-level test showing that UPDATE and DELETE raise errors

## 3. Request parsing and validation (spec: journal/transaction-posting)

- [x] 3.1 Create request/response records (envelope with `referenceId`, entries, and a `versions` list in the response) and a strict Jackson config (fail on unknown properties, floats given for ints, and nulls for primitives). Verify with web-layer tests that an unknown field, a fractional amount and a missing direction each return `400 MALFORMED_REQUEST`
- [x] 3.2 Implement account id parsing to `CUSTOMER`/`INTERNAL` using the D3 regexes. Verify with unit tests for valid `cus_`/`int_` ids and for rejected `acct_42`, `cus_42` and `int_settlement`
- [x] 3.3 Implement the entry rules: bucket required/forbidden by type and only `AVAILABLE`/`HELD` (D9), amount 1..10^15, ISO 4217 currency with fraction digits >= 0, 2..100 entries, and balanced per currency. Verify with unit tests, one per spec scenario (`INVALID_BUCKET` for missing, forbidden and unknown values, `INVALID_AMOUNT`, `UNKNOWN_CURRENCY`, `TOO_FEW_ENTRIES`, `TOO_MANY_ENTRIES`, `UNBALANCED`, multi-currency accepted)
- [x] 3.4 Add a `@RestControllerAdvice` that produces RFC 9457 `ProblemDetail` with `code` and `line`, and a missing or empty `Idempotency-Key` returning `400 MISSING_IDEMPOTENCY_KEY` and an invalid one `400 MALFORMED_REQUEST`. Verify with web-layer tests that check the content type and body

## 4. Posting write path (specs: both)

- [x] 4.1 Implement net-delta computation per customer (account, currency), sorted, with internal entries skipped. Verify with unit tests for the hold, FX-on-one-customer, several-entries-one-key and internal-only cases
- [x] 4.2 Implement the write (D5, D7) in one `@Transactional` method: generate the UUIDv7 id, send the sorted upserts as one JDBC batch (no `reWriteBatchedInserts`), reading `version` from generated keys in batch order, and skip the batch when there are no customer balances; then run the transaction insert with `id` and `versions`, returning `posted_at`. Verify with Testcontainers tests covering a first posting creating balances, the hold scenario, FX on one customer (two balances, two versions), a posting touching three balances (transfer plus fee) with the right version on each balance, negative balances, an internal-only posting (empty `versions`, no balance rows), one version increment per posting, every changed balance having `last_transaction_id` set to the posting's id while untouched balances keep theirs, and an omitted `occurredAt` equal to `postedAt`
- [x] 4.3 Wire `POST /transactions` with idempotency (D6): no lookup before writing; a unique violation on `idempotency_key` rolls back and returns `409 IDEMPOTENCY_KEY_REUSED`. Verify with integration tests showing that a retry with the same body and a reused key with a different body both get `409` with only one posting recorded, and that balances and versions are unchanged after the `409`
- [x] 4.4 Add concurrency integration tests: N parallel postings on the same `cus_` balance produce contiguous versions 1..N and the correct final balance; two concurrent requests with the same key give one `201` and one `409` with one posting recorded; concurrent postings touching `cus_A`+`cus_B` and `cus_B`+`cus_A` both succeed without deadlock errors, showing that the batch keeps the sorted lock order; postings on different balances don't block each other. Verify with a green test run
- [x] 4.5 Add a balance-reconstruction test: after a mixed sequence of postings, summing `entries` from `journal.transaction` per key and bucket equals `journal.balance`, the last recorded `versions` per balance equal `journal.balance.version`, and for every balance the posting named by `last_transaction_id` lists that balance in its `versions` with the same version. Verify with a green test run

## 5. Integration check

- [x] 5.1 Run `./gradlew build` at the repo root and `docker compose up --build`, then `curl` a balanced posting to `localhost:8080/transactions` twice with the same key. Verify the first returns `201` and the second `409 IDEMPOTENCY_KEY_REUSED`

## 6. Docs

- [x] 6.1 Create `services/journal/docs/data-model.md` (ER diagram and invariants) and `services/journal/docs/posting.md` (write-path sequence diagram), starting from design.md and updated to match the implemented code, and add both to the index in `docs/README.md`. Verify that the Mermaid renders and the links work
