# journal/transaction-posting Specification

## Purpose

Lets upstream systems record balanced double-entry postings in the journal through a REST contract. Postings are append-only, idempotent and validated.

## Requirements

### Requirement: Record a posting
The journal SHALL expose `POST /transactions` accepting a JSON posting with optional `referenceId` (≤128 chars, not unique, links related postings), `occurredAt` (RFC 3339 timestamp), `description` (≤500 chars), `metadata` (JSON object), and a required `entries` array. On success it SHALL respond `201 Created` with the recorded posting.

#### Scenario: Valid posting is recorded
- **WHEN** a client posts two entries that debit and credit 5000 EUR minor units between two valid accounts with a new `Idempotency-Key`
- **THEN** the response is `201 Created`
- **AND** the body contains a server-assigned `id`, a server-assigned `postedAt`, the submitted envelope fields, the entries and `versions`

#### Scenario: referenceId shared by related postings
- **WHEN** two postings are recorded with the same `referenceId`
- **THEN** both are recorded

#### Scenario: occurredAt defaults to postedAt
- **WHEN** a valid posting omits `occurredAt`
- **THEN** the recorded `occurredAt` equals its `postedAt`

### Requirement: Malformed requests rejected
A request body that does not follow the contract SHALL be rejected with `400` and code `MALFORMED_REQUEST`: an unknown field, a missing required field, a value of the wrong JSON type, or an envelope string over its length limit.

#### Scenario: Unknown fields are rejected
- **WHEN** the body contains a field that is not part of the contract (for example `kind`)
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

#### Scenario: Over-long envelope field rejected
- **WHEN** `referenceId` is longer than 128 characters or `description` longer than 500
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

#### Scenario: Wrong JSON type rejected
- **WHEN** an entry has `amount` "5000" (a string)
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

#### Scenario: Number in a text field rejected
- **WHEN** an entry has `currency` 978 or `bucket` 1, or the envelope has `referenceId` 1 (a number, not a string)
- **THEN** the response is `400` with code `MALFORMED_REQUEST`, not a domain error

### Requirement: Entry shape
Each entry SHALL contain `accountId`, `direction` (`DEBIT` or `CREDIT`), `amount` and `currency`, plus `bucket` when required by the account type. In the response, each entry SHALL also carry its 1-based `line` (its position in the request) and the derived `accountType`.

#### Scenario: Response entries are numbered and typed
- **WHEN** a valid posting with entries for `cus_…` and `int_SETTLEMENT_VISA` is recorded
- **THEN** the response entries keep request order with `line` 1 and 2
- **AND** carry `accountType` `CUSTOMER` and `INTERNAL` respectively

#### Scenario: Missing direction is rejected
- **WHEN** an entry has no `direction` or a value other than `DEBIT`/`CREDIT`
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

#### Scenario: Missing required entry field rejected
- **WHEN** an entry omits `accountId`, `amount` or `currency`
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

### Requirement: Account id grammar determines account type
An `accountId` SHALL be either `cus_` followed by a 26-character TypeID suffix (lowercase Crockford base32 of a UUIDv7, first character `0`-`7`), which is a customer account, or `int_` followed by `[A-Z][A-Z0-9_]{2,63}`, which is an internal account. The type SHALL be derived from the prefix and SHALL NOT be accepted from the client.

#### Scenario: Customer account id accepted
- **WHEN** an entry uses `cus_01jcz8k2m4f7r9t3vbx5w6y8za`
- **THEN** the entry is treated as a `CUSTOMER` account

#### Scenario: Internal account id accepted
- **WHEN** an entry uses `int_SETTLEMENT_VISA`
- **THEN** the entry is treated as an `INTERNAL` account

#### Scenario: Unrecognised account id rejected
- **WHEN** an entry uses `acct_42`, `cus_42` or `int_settlement`
- **THEN** the response is `422` with code `INVALID_ACCOUNT_ID` identifying the entry

### Requirement: Buckets on customer entries
An entry for a customer account SHALL carry `bucket` with value `AVAILABLE` or `HELD`. An entry for an internal account SHALL NOT carry `bucket`. `bucket` SHALL never be `null`: an explicit `null` is rejected with `400` and code `MALFORMED_REQUEST`, whatever the account type.

#### Scenario: Customer entry without bucket rejected
- **WHEN** a `cus_` entry omits `bucket`
- **THEN** the response is `422` with code `INVALID_BUCKET`

#### Scenario: Internal entry with bucket rejected
- **WHEN** an `int_` entry carries `bucket`
- **THEN** the response is `422` with code `INVALID_BUCKET`

#### Scenario: Null bucket rejected
- **WHEN** a `cus_` or `int_` entry carries `bucket` `null`
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

#### Scenario: Unknown bucket value rejected
- **WHEN** a `cus_` entry has `bucket` `PENDING`
- **THEN** the response is `422` with code `INVALID_BUCKET`

### Requirement: Amounts in minor units
`amount` SHALL be a JSON integer greater than 0 and at most 10^15, expressed in the minor units of the entry's `currency`. For example, 5730 means 57.30 EUR and 500 means 500 JPY.

#### Scenario: Zero or negative amount rejected
- **WHEN** an entry has `amount` 0 or -100
- **THEN** the response is `422` with code `INVALID_AMOUNT`

#### Scenario: Fractional amount rejected
- **WHEN** an entry has `amount` 57.3
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

### Requirement: Currency codes
`currency` SHALL be an ISO 4217 alphabetic code that has minor units defined. Entries in one posting MAY use different currencies.

#### Scenario: Unknown currency rejected
- **WHEN** an entry has `currency` `XYZ`
- **THEN** the response is `422` with code `UNKNOWN_CURRENCY`

### Requirement: Posting balances per currency
A posting SHALL contain at least two entries and at most 100. For each currency in the posting, the sum of `DEBIT` amounts SHALL equal the sum of `CREDIT` amounts.

#### Scenario: Unbalanced posting rejected
- **WHEN** a posting debits 5730 EUR and credits 5719 EUR
- **THEN** the response is `422` with code `UNBALANCED` naming the currency and both sums
- **AND** nothing is recorded

#### Scenario: Multi-currency posting balanced per currency
- **WHEN** a posting debits and credits 11000 USD and separately debits and credits 10000 EUR
- **THEN** it is recorded

#### Scenario: Single entry rejected
- **WHEN** a posting has one entry
- **THEN** the response is `422` with code `TOO_FEW_ENTRIES`

#### Scenario: Too many entries rejected
- **WHEN** a posting has 101 entries
- **THEN** the response is `422` with code `TOO_MANY_ENTRIES`

### Requirement: Idempotency key is required
Every `POST /transactions` SHALL carry an `Idempotency-Key` header of 1 to 255 printable ASCII characters. Keys are unique for the lifetime of the ledger, with no expiry.

#### Scenario: Missing key rejected
- **WHEN** a posting is sent without `Idempotency-Key`
- **THEN** the response is `400` with code `MISSING_IDEMPOTENCY_KEY`

#### Scenario: Empty key rejected
- **WHEN** `Idempotency-Key` is present but empty
- **THEN** the response is `400` with code `MISSING_IDEMPOTENCY_KEY`

#### Scenario: Invalid key rejected
- **WHEN** `Idempotency-Key` is longer than 255 characters or contains non-printable or non-ASCII characters
- **THEN** the response is `400` with code `MALFORMED_REQUEST`

### Requirement: Duplicate idempotency key rejected
A request whose `Idempotency-Key` is already recorded SHALL be rejected with `409` and code `IDEMPOTENCY_KEY_REUSED`, whatever its body, and SHALL record nothing. A `409` tells the client that a posting with that key already exists.

#### Scenario: Retry with same body rejected
- **WHEN** the same request with the same key is sent twice
- **THEN** the first response is `201` and the second is `409` with code `IDEMPOTENCY_KEY_REUSED`
- **AND** only one posting exists

#### Scenario: Concurrent duplicates record once
- **WHEN** two requests with the same key arrive concurrently
- **THEN** exactly one posting is recorded, one response is `201` and the other is `409`

#### Scenario: Key reused with different body
- **WHEN** a recorded key is sent with a different amount
- **THEN** the response is `409` with code `IDEMPOTENCY_KEY_REUSED`

### Requirement: Recorded postings are immutable
A recorded posting and its entries SHALL never be modified or deleted. Corrections are made by recording new postings.

#### Scenario: No update path
- **WHEN** any client attempts to change a recorded posting
- **THEN** no API exists for it and the database rejects updates and deletes on the posting log

### Requirement: Error format
Error responses SHALL use RFC 9457 `application/problem+json` with `status`, `title`, a machine-readable `code`, and, for entry-level problems, the offending entry's `line`. A posting that fails validation SHALL record nothing.

#### Scenario: Validation error body
- **WHEN** entry 2 has an invalid account id
- **THEN** the response has content type `application/problem+json`, `code` `INVALID_ACCOUNT_ID` and identifies line 2
