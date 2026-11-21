# Proposal

## Why

The ledger has no business behaviour yet: `journal` is an empty Spring Boot app with an empty `journal` schema. Every later feature (card flows, CDC to `query`, balance inquiry) needs a way to record a balanced double-entry posting, so the first change defines that write path and its REST contract. The table shape is decided now too: once the log is append-only and streamed through CDC, changing it later is expensive.

## What Changes

- New endpoint `POST /transactions` on `journal-app` that records a generic double-entry posting. The posting carries an envelope (`referenceId`, `occurredAt`, `description`, `metadata`) and two or more entries (`accountId`, `bucket`, `direction`, `amount`, `currency`).
- Amounts are positive integers in the currency's minor units, with an explicit `DEBIT`/`CREDIT` direction. A posting must balance per currency, so it can mix currencies (FX).
- The account type is derived from the account id prefix, never sent separately. `cus_<typeid>` is a customer account and `int_<CODE>` is an internal account.
- Customer entries carry a bucket (`AVAILABLE` or `HELD`). Internal entries do not.
- The `Idempotency-Key` header is mandatory. Reusing an `Idempotency-Key` is rejected with `409`. Replaying the original result may come later.
- `journal` keeps a current balance per customer account and currency (`available`, `held`, `version`), updated in the same database transaction as the posting. Each recorded posting lists the new `version` of every customer balance it changed, so downstream consumers can order postings per balance and detect duplicates and gaps. Internal accounts get no balance row and take no lock. Each balance also records `last_transaction_id`, the posting that last changed it. A future CDC stream of the balance table then gives downstream a balance history: the balance after each posting.
- The posting log is a single append-only table, with entries stored as `jsonb` on the transaction row. A future CDC connector will therefore see one atomic event per posting, and one event per changed balance per posting.
- Errors use RFC 9457 `application/problem+json` with a machine-readable `code`.
- Out of scope: per-kind posting rules (no `kind` field yet), funds checks or conditions (no `INSUFFICIENT_FUNDS`), the Debezium connector, the Avro event contract, `query` projections, read endpoints, and authentication.

## Capabilities

### New Capabilities
- `journal/transaction-posting`: The REST contract and validation for recording a balanced double-entry posting. It covers the account id grammar, buckets, minor-unit amounts, per-currency balancing, idempotency, and error responses.
- `journal/account-balances`: Current balances of customer accounts (available/held per currency) maintained by `journal`, plus per-balance versioning recorded on each posting.

### Modified Capabilities
- none

## Impact

- `services/journal/journal-app`: new web, validation, persistence and Liquibase changesets (tables `journal.transaction` and `journal.balance`). It needs JDBC access, so a JDBC starter dependency is added.
- No change to `query`, `journal-events`, Kafka or Debezium. The CDC-friendly table shape is in place, but no connector is registered.
- New public API: `POST /transactions` on port 8080.

## Docs impact

- Create `services/journal/docs/data-model.md`: an ER diagram of `journal.transaction` and `journal.balance` and their invariants. It is shared by every journal flow and evolves with the schema.
- Create `services/journal/docs/posting.md`: a sequence diagram of the posting write path, linking to the data model.
- Update `docs/README.md`: add both docs to the index.
