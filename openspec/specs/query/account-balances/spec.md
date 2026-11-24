# query/account-balances Specification

## Purpose

Keeps, on the read side, every version of each customer balance per account and currency, linked to the posting that produced it, built from the journal's balance change events. `query` can then serve the current balance and the balance at a given transaction without touching the journal's database.

## Requirements

### Requirement: Every balance version kept
For every event on `ledger.journal.balance`, `query` SHALL hold that version of the balance: `available_debit`, `available_credit`, `held_debit`, `held_credit`, `version` and `last_transaction_id`. The current balance of an account and currency SHALL be its highest held `version`. Versions published before `query` first started SHALL be included.

#### Scenario: All versions held, highest is current
- **WHEN** `query` consumes `cus_A` EUR versions 1, 2 and 3
- **THEN** it holds all three versions, each with the totals and `last_transaction_id` of its event
- **AND** the current `cus_A` EUR balance is version 3

#### Scenario: Versions linked to their posting
- **WHEN** FX posting `T1` publishes `cus_A` EUR version 18 and `cus_A` USD version 4
- **THEN** the versions held with `last_transaction_id` `T1` are exactly `cus_A` EUR 18 and `cus_A` USD 4

#### Scenario: Rebuilt from the start
- **GIVEN** balance events were published while `query` had never run
- **WHEN** `query` starts with an empty database
- **THEN** it holds every published version of each of those balances

### Requirement: Repeated versions ignored
A balance event for an account, currency and `version` that `query` already holds SHALL change nothing. A version that arrives after a higher one and is not yet held SHALL be stored and SHALL NOT change the current balance.

#### Scenario: Redelivered version
- **GIVEN** `query` holds `cus_A` EUR versions 1 to 3
- **WHEN** the version 3 event is consumed again
- **THEN** `cus_A` EUR still has exactly one version 3, unchanged

#### Scenario: Missing older version arrives late
- **GIVEN** `query` holds `cus_A` EUR versions 1 and 3 only
- **WHEN** the version 2 event is consumed
- **THEN** version 2 is held and the current `cus_A` EUR balance is still version 3

### Requirement: No balance event is skipped
`query` SHALL NOT skip a balance event it cannot process. Ingestion of the affected partition SHALL stop and be retried until the event can be processed.

#### Scenario: Undecodable balance event blocks later versions
- **GIVEN** an event on `ledger.journal.balance` that cannot be decoded is followed by `cus_A` EUR version 5
- **WHEN** `query` consumes the topic
- **THEN** `cus_A` EUR version 5 is not held while the undecodable event is not processed
