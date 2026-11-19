# Spec Delta

## Purpose

Maintains the current available and held balance of each customer account per currency in the journal, updated atomically with every posting, and versioned so downstream consumers can order and verify balance changes. It also records which posting last changed each balance, so a balance history can be derived downstream.

## ADDED Requirements

### Requirement: Customer balance per account and currency
The journal SHALL maintain, for each customer account and currency that has appeared in a recorded posting, an `available` and a `held` balance in minor units. It is created on first use, starting from 0.

#### Scenario: First posting creates the balance
- **WHEN** the first posting for `cus_A` in EUR credits `AVAILABLE` 5000
- **THEN** `cus_A` EUR has available 5000 and held 0

#### Scenario: Currencies are independent
- **WHEN** postings credit `cus_A` `AVAILABLE` 5000 EUR and 11000 USD
- **THEN** `cus_A` has a separate EUR balance of 5000 and USD balance of 11000

### Requirement: Entry effect on balance
Balances SHALL use the customer's perspective. A `CREDIT` entry SHALL increase the balance of its bucket by the amount, and a `DEBIT` entry SHALL decrease it.

#### Scenario: Hold moves money between buckets
- **GIVEN** `cus_A` EUR available 10000, held 0
- **WHEN** a posting debits `cus_A` `AVAILABLE` 5000 and credits `cus_A` `HELD` 5000
- **THEN** available is 5000 and held is 5000

#### Scenario: FX on one customer changes two balances
- **GIVEN** `cus_A` EUR available 45000 and USD available 2000
- **WHEN** a posting debits `cus_A` `AVAILABLE` 10000 EUR, credits `int_FX_POSITION_EUR` 10000 EUR, debits `int_FX_POSITION_USD` 11000 USD and credits `cus_A` `AVAILABLE` 11000 USD
- **THEN** `cus_A` EUR available is 35000 and USD available is 13000

### Requirement: Balances may go negative
The journal SHALL NOT reject a posting because of a resulting balance. Balances MAY become negative.

#### Scenario: Overdraw recorded
- **GIVEN** `cus_A` EUR available 1000
- **WHEN** a balanced posting debits `cus_A` `AVAILABLE` 5000
- **THEN** it is recorded and available is -4000

### Requirement: Internal accounts have no maintained balance
The journal SHALL NOT maintain a balance or version for internal (`int_`) accounts.

#### Scenario: Internal-only posting changes no balance
- **WHEN** a posting debits `int_SETTLEMENT_VISA` and credits `int_BANK_NOSTRO_EUR`
- **THEN** it is recorded with an empty `versions` list and no balance exists for either account

### Requirement: Balance version
Each customer balance (account and currency) SHALL have a `version` that starts at 1 with the first posting touching it and increases by exactly 1 with each later posting touching it, regardless of how many entries of that posting hit it. The version order SHALL match the commit order of those postings.

#### Scenario: Versions are contiguous
- **WHEN** three postings touch `cus_A` EUR in sequence
- **THEN** they record `cus_A` EUR versions 1, 2 and 3

#### Scenario: Concurrent postings serialize
- **WHEN** two postings touching `cus_A` EUR are submitted concurrently
- **THEN** both are recorded with distinct consecutive versions and the final balance reflects both

#### Scenario: Several entries, one increment
- **WHEN** one posting has a `HELD` and two `AVAILABLE` entries on `cus_A` EUR
- **THEN** `cus_A` EUR version increases by exactly 1

### Requirement: Versions recorded on the posting
Each recorded posting SHALL carry `versions`, a list with exactly one item `{accountId, currency, version}` for every customer balance it changed, giving that balance's version after the posting. Internal accounts SHALL NOT appear in it.

#### Scenario: FX posting lists both balances
- **GIVEN** `cus_A` EUR at version 17 and `cus_A` USD at version 3
- **WHEN** the FX posting from "FX on one customer changes two balances" is recorded
- **THEN** its `versions` are `cus_A` EUR 18 and `cus_A` USD 4, and no `int_` account is listed

### Requirement: Balance records the posting that changed it
After a posting is recorded, every customer balance it changed SHALL hold that posting's `id` as its last transaction id, together with the new `version`, `available` and `held`. A balance the posting did not change SHALL keep its previous last transaction id.

#### Scenario: FX posting stamps both balances
- **WHEN** the FX posting from "FX on one customer changes two balances" is recorded with id `T1`
- **THEN** `cus_A` EUR and `cus_A` USD both have last transaction id `T1`

#### Scenario: Untouched balance keeps its last posting
- **GIVEN** `cus_A` EUR has last transaction id `T1`
- **WHEN** a posting `T2` touching only `cus_B` is recorded
- **THEN** `cus_A` EUR still has last transaction id `T1`

### Requirement: Atomic with the posting
Balance changes SHALL be applied in the same atomic unit as recording the posting. A rejected posting, including a request rejected for a duplicate key, SHALL NOT change any balance or version.

#### Scenario: Rejected posting leaves balances untouched
- **WHEN** an unbalanced posting touching `cus_A` is rejected
- **THEN** `cus_A` balances and versions are unchanged

#### Scenario: Duplicate key leaves balances untouched
- **WHEN** a request touching `cus_A` reuses an already recorded `Idempotency-Key` and is rejected with `409`
- **THEN** `cus_A` balances and versions are unchanged

### Requirement: Balances are derivable from the log
For every customer account, bucket and currency, the maintained balance SHALL equal the sum of credits minus debits of all recorded entries for that key.

#### Scenario: Reconstruction matches
- **WHEN** balances are recomputed from all recorded postings
- **THEN** they equal the maintained balances
