# query/transaction-search Specification

## Purpose

Lets clients find the postings that touched an account, customer or internal, through the read side's REST API, using a projection that `query` builds from the journal's posting change events without touching the journal's database.

## Requirements

### Requirement: Postings ingested from change events
`query` SHALL make every posting published on `ledger.journal.transaction` searchable, including postings published before `query` first started. A posting recorded in the journal SHALL eventually become searchable; no read-your-writes guarantee is given.

#### Scenario: Recorded posting becomes searchable
- **WHEN** posting `T1` debiting `cus_A` `AVAILABLE` 5000 EUR and crediting `int_SETTLEMENT_VISA` 5000 EUR is recorded in the journal
- **THEN** `GET /accounts/cus_A/transactions` eventually returns `T1`

#### Scenario: Postings published before query started
- **GIVEN** postings were published while `query` had never run
- **WHEN** `query` starts with an empty database
- **THEN** each of those postings becomes searchable by every account it touched

### Requirement: Duplicate posting events ignored
An event for a posting `id` that `query` already holds SHALL change nothing, so a posting delivered more than once appears once in search results.

#### Scenario: Redelivered posting appears once
- **WHEN** the event for posting `T1` touching `cus_A` is consumed twice
- **THEN** `GET /accounts/cus_A/transactions` returns `T1` exactly once

### Requirement: No posting event is skipped
`query` SHALL NOT skip an event it cannot process. Ingestion of the affected partition SHALL stop and be retried until the event can be processed, so later postings become searchable only after it.

#### Scenario: Undecodable event blocks later postings
- **GIVEN** an event on `ledger.journal.transaction` that cannot be decoded is followed by posting `T2`
- **WHEN** `query` consumes the topic
- **THEN** `T2` does not become searchable while the undecodable event is not processed
- **AND** postings consumed before it remain searchable

### Requirement: Search transactions by account
`query` SHALL expose `GET /accounts/{accountId}/transactions`, returning `200` with `items`, the postings having at least one entry whose `accountId` equals `{accountId}`, and `nextCursor`. Both customer (`cus_`) and internal (`int_`) accounts SHALL be searchable. A posting SHALL appear once however many of its entries touch the account.

#### Scenario: Customer account search
- **GIVEN** `T1` touches `cus_A` and `int_SETTLEMENT_VISA`, and `T2` touches only `cus_B` and `int_BANK_NOSTRO_EUR`
- **WHEN** a client calls `GET /accounts/cus_A/transactions`
- **THEN** the response is `200` and `items` holds `T1` only

#### Scenario: Internal account search
- **GIVEN** `T1` and `T3` both credit `int_SETTLEMENT_VISA`
- **WHEN** a client calls `GET /accounts/int_SETTLEMENT_VISA/transactions`
- **THEN** `items` holds `T1` and `T3`

#### Scenario: Several entries on the account, one item
- **GIVEN** hold posting `T4` debits `cus_A` `AVAILABLE` and credits `cus_A` `HELD`
- **WHEN** a client searches `cus_A`
- **THEN** `T4` appears exactly once

#### Scenario: Account with no postings
- **WHEN** a client searches a well-formed account id that no posting touched
- **THEN** the response is `200` with empty `items` and `nextCursor` null

### Requirement: Search item shape
Each item SHALL be the recorded posting with the same shape as the journal's `201` response: `id`, `referenceId`, `occurredAt`, `postedAt`, `description`, `metadata`, all of its `entries` in line order (not only those of the searched account) and `versions`. Timestamps SHALL be RFC 3339 in UTC.

#### Scenario: Item equals the recorded posting
- **WHEN** posting `T1` is recorded with a `referenceId`, `metadata` and two entries, and later found by search
- **THEN** its item equals the journal's `201` response body for `T1`, timestamps compared as instants

#### Scenario: Entries of other accounts included
- **WHEN** FX posting `T5` touching `cus_A`, `int_FX_POSITION_EUR` and `int_FX_POSITION_USD` is found by searching `cus_A`
- **THEN** its item lists all four entries

### Requirement: Search ordering
Items SHALL be ordered by `occurredAt` descending and, for equal `occurredAt`, by `id` descending. The order SHALL be stable across pages.

#### Scenario: Newest occurrence first
- **GIVEN** `cus_A` postings `T1` occurred at 2026-11-20T10:00Z and `T2` at 2026-11-21T10:00Z
- **WHEN** a client searches `cus_A`
- **THEN** `T2` comes before `T1`

#### Scenario: Backdated posting sorts by occurrence
- **GIVEN** `T2` is recorded after `T1` with an `occurredAt` earlier than `T1`'s
- **WHEN** a client searches the account they share
- **THEN** `T1` comes before `T2`

### Requirement: Currency filter
An optional `currency` query parameter (ISO 4217 alphabetic code) SHALL restrict items to postings with at least one entry for `{accountId}` in that currency.

#### Scenario: FX posting matches both currencies
- **GIVEN** FX posting `T5` debits `cus_A` in EUR and credits `cus_A` in USD, and `T1` touches `cus_A` only in EUR
- **WHEN** a client searches `cus_A` with `currency=USD`
- **THEN** `items` holds `T5` only

#### Scenario: Currency of another account does not match
- **GIVEN** `T6` touches `cus_A` only in EUR and `int_FX_POSITION_USD` in USD
- **WHEN** a client searches `cus_A` with `currency=USD`
- **THEN** `T6` is not returned

### Requirement: Occurrence time range filter
Optional `occurredFrom` (inclusive) and `occurredTo` (exclusive) query parameters, RFC 3339 timestamps, SHALL restrict items to postings whose `occurredAt` lies in that range. Either bound MAY be given alone.

#### Scenario: Bounds are inclusive-exclusive
- **GIVEN** `cus_A` postings occurred at 10:00Z, 11:00Z and 12:00Z on 2026-11-21
- **WHEN** a client searches `cus_A` with `occurredFrom=2026-11-21T10:00:00Z` and `occurredTo=2026-11-21T12:00:00Z`
- **THEN** `items` holds the 11:00Z and 10:00Z postings, in that order

#### Scenario: Empty range rejected
- **WHEN** `occurredFrom` is not before `occurredTo`
- **THEN** the response is `400` with code `INVALID_PARAMETER` and `parameter` `occurredTo`

### Requirement: Cursor paging
An optional `limit` (1–100, default 20) SHALL cap the number of items. `nextCursor` SHALL be an opaque string when more items follow, and null otherwise. Passing it as `cursor` with the same filters SHALL return the next page, with no item repeated or skipped among postings ingested before the first page was read.

#### Scenario: Walk all pages
- **GIVEN** `cus_A` has 45 postings
- **WHEN** a client searches with `limit=20` and follows `nextCursor` until it is null
- **THEN** it reads 20, 20 and 5 items, each posting exactly once, in search order

#### Scenario: Default limit
- **GIVEN** `cus_A` has 25 postings
- **WHEN** a client searches without `limit`
- **THEN** `items` holds 20 postings and `nextCursor` is not null

#### Scenario: Last page exactly full
- **GIVEN** `cus_A` has 20 postings
- **WHEN** a client searches with `limit=20`
- **THEN** `items` holds all 20 postings and `nextCursor` is null

### Requirement: Invalid search parameters rejected
A request SHALL be rejected with `400` as an RFC 9457 problem with code `INVALID_PARAMETER` and `parameter` naming the offending input when: `accountId` does not follow the journal's account id grammar, `currency` is not three upper-case letters, a timestamp is not RFC 3339, `limit` is outside 1–100, or `cursor` was not issued by `query`.

#### Scenario: Malformed account id
- **WHEN** a client calls `GET /accounts/acct_42/transactions`
- **THEN** the response is `400` with code `INVALID_PARAMETER` and `parameter` `accountId`

#### Scenario: Limit out of range
- **WHEN** a client searches with `limit=0` or `limit=101`
- **THEN** the response is `400` with code `INVALID_PARAMETER` and `parameter` `limit`

#### Scenario: Tampered cursor
- **WHEN** a client searches with `cursor=not-a-cursor`
- **THEN** the response is `400` with code `INVALID_PARAMETER` and `parameter` `cursor`
