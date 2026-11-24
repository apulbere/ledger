# Spec Delta

## Purpose

Keeps, on the read side, the current state of each customer balance per account and currency, built from the journal's balance change events, so `query` can serve balance reads without touching the journal's database.

## ADDED Requirements

### Requirement: Current balance per account and currency
For every customer account and currency with an event on `ledger.journal.balance`, `query` SHALL hold the state of the highest `version` it has consumed: `available_debit`, `available_credit`, `held_debit`, `held_credit`, `version` and `last_transaction_id`. Balances published before `query` first started SHALL be included.

#### Scenario: Latest version held
- **WHEN** `query` consumes `cus_A` EUR versions 1, 2 and 3
- **THEN** it holds `cus_A` EUR at version 3 with the totals and `last_transaction_id` of the version 3 event

#### Scenario: Currencies kept apart
- **WHEN** FX posting `T1` publishes `cus_A` EUR version 18 and `cus_A` USD version 4
- **THEN** `query` holds both balances, each with `last_transaction_id` `T1`

#### Scenario: Rebuilt from the start
- **GIVEN** balance events were published while `query` had never run
- **WHEN** `query` starts with an empty database
- **THEN** it holds the latest published version of each of those balances

### Requirement: Older or repeated balance versions ignored
A balance event whose `version` is not higher than the held `version` for that account and currency SHALL change nothing.

#### Scenario: Redelivered version
- **GIVEN** `query` holds `cus_A` EUR at version 3
- **WHEN** the version 3 event is consumed again
- **THEN** `cus_A` EUR is unchanged

#### Scenario: Stale version after a newer one
- **GIVEN** `query` holds `cus_A` EUR at version 3
- **WHEN** a version 2 event for `cus_A` EUR is consumed
- **THEN** `cus_A` EUR stays at version 3 with its version 3 totals

### Requirement: No balance event is skipped
`query` SHALL NOT skip a balance event it cannot process. Ingestion of the affected partition SHALL stop and be retried until the event can be processed.

#### Scenario: Undecodable balance event blocks later versions
- **GIVEN** an event on `ledger.journal.balance` that cannot be decoded is followed by `cus_A` EUR version 5
- **WHEN** `query` consumes the topic
- **THEN** `cus_A` EUR does not reach version 5 while the undecodable event is not processed
