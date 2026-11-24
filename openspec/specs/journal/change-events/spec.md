# journal/change-events Specification

## Purpose

Publishes every recorded posting and every customer balance change from the journal to Kafka, under a stable Avro contract, so read-side services can build projections such as balance inquiry and transaction search without touching the journal's database.

## Requirements

### Requirement: One event per recorded posting
For every recorded posting, the journal SHALL publish one event to topic `ledger.journal.transaction`, keyed by the posting `id`. The event SHALL carry the recorded posting: `id`, `idempotency_key`, `reference_id`, `occurred_at`, `posted_at`, `description`, `metadata`, `entries` and `versions`.

#### Scenario: Recorded posting is published
- **WHEN** a posting `T1` debiting `cus_A` `AVAILABLE` 5000 EUR and crediting `int_SETTLEMENT_VISA` 5000 EUR is recorded
- **THEN** exactly one event with key `T1` appears on `ledger.journal.transaction`
- **AND** its `entries` hold both entries with `line` and `accountType`, and its `versions` hold `cus_A` EUR with the version the posting returned

#### Scenario: Rejected posting publishes nothing
- **WHEN** a posting is rejected (validation error or reused `Idempotency-Key`)
- **THEN** no event for it appears on either topic

### Requirement: JSON-valued posting fields
`entries`, `versions` and `metadata` SHALL be JSON text with the same shape as in the REST response of the recorded posting. `metadata` SHALL be absent (null) when the posting had none. Timestamps SHALL be RFC 3339 strings in UTC.

#### Scenario: Entries parse to the REST shape
- **WHEN** the event for a posting is read and its `entries` text is parsed as JSON
- **THEN** it equals the `entries` array of the `201` response for that posting

### Requirement: One event per customer balance change
For every customer balance a recorded posting changes, the journal SHALL publish one event to topic `ledger.journal.balance`, keyed by `account_id` and `currency`. It SHALL carry the balance state after that posting: `available_debit`, `available_credit`, `held_debit`, `held_credit`, `version` and `last_transaction_id`. Internal accounts SHALL produce no balance event.

#### Scenario: FX posting publishes two balance events
- **WHEN** the FX posting `T1` from journal/account-balances is recorded, changing `cus_A` EUR to version 18 and `cus_A` USD to version 4
- **THEN** `ledger.journal.balance` receives one event keyed `cus_A`/EUR with version 18 and one keyed `cus_A`/USD with version 4, both with `last_transaction_id` `T1` and their full totals

#### Scenario: Internal-only posting publishes no balance event
- **WHEN** a posting touching only `int_` accounts is recorded
- **THEN** its posting event is published and no balance event is

### Requirement: Balance events ordered per balance
Balance events for one account and currency SHALL be delivered in increasing `version` order. No order SHALL be guaranteed across different balances, across postings on `ledger.journal.transaction`, or between the two topics; consumers relate a balance event to its posting by `last_transaction_id`.

#### Scenario: Versions arrive in order
- **WHEN** three postings touch `cus_A` EUR in sequence
- **THEN** a consumer of `ledger.journal.balance` reads `cus_A` EUR versions 1, 2 and 3 in that order

### Requirement: At-least-once delivery
Every committed posting and balance change SHALL eventually be published, including changes committed while Kafka or the connector were unavailable. An event MAY be delivered more than once; a duplicate is identified by posting `id` or by `account_id`, `currency` and `version`.

#### Scenario: Changes during an outage are published after recovery
- **GIVEN** the connector is stopped
- **WHEN** postings are recorded and the connector is started again
- **THEN** their posting and balance events are published

### Requirement: Posting does not depend on publishing
Recording a posting SHALL NOT wait on, or fail because of, Kafka, Schema Registry or the connector.

#### Scenario: Posting while Kafka is down
- **GIVEN** Kafka is unavailable
- **WHEN** a valid posting is submitted
- **THEN** the response is `201 Created`

### Requirement: Existing data is published
When the connector starts for the first time, postings and balances recorded before then SHALL be published as well: one posting event per existing posting and one balance event with the current state of each existing balance.

#### Scenario: Postings before the connector existed
- **GIVEN** postings were recorded before the connector was first registered
- **WHEN** the connector starts
- **THEN** each of those postings has an event on `ledger.journal.transaction` and each existing balance has an event on `ledger.journal.balance`

### Requirement: Events retained indefinitely
Both topics SHALL keep every event without time- or size-based deletion and without compaction, so a new consumer reading from the beginning sees every posting and every balance version.

#### Scenario: New consumer rebuilds from the start
- **WHEN** a new consumer group reads `ledger.journal.balance` from the earliest offset
- **THEN** it receives every version of every customer balance since the first posting

### Requirement: Avro contract
Keys and values on both topics SHALL be Avro, with schemas registered in Schema Registry. Their schemas SHALL match the hand-written contract in `journal-events`: `TransactionKey` and `TransactionPosted` for postings, `BalanceKey` and `BalanceChanged` for balances, in namespace `ledger.journal.events`.

#### Scenario: Registered schema matches the contract
- **WHEN** an event is read from either topic
- **THEN** its key and value writer schemas have the same Avro parsing canonical form as the corresponding `journal-events` schemas
