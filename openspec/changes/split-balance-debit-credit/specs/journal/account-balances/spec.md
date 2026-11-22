# Spec Delta

## MODIFIED Requirements

### Requirement: Customer balance per account and currency
The journal SHALL maintain, for each customer account and currency that has appeared in a recorded posting, four totals in minor units: `available_debit`, `available_credit`, `held_debit` and `held_credit`. It is created on first use, starting from 0. A bucket's balance is its credit total minus its debit total.

#### Scenario: First posting creates the balance
- **WHEN** the first posting for `cus_A` in EUR credits `AVAILABLE` 5000
- **THEN** `cus_A` EUR has available credit 5000, available debit 0, held credit 0 and held debit 0

#### Scenario: Currencies are independent
- **WHEN** postings credit `cus_A` `AVAILABLE` 5000 EUR and 11000 USD
- **THEN** `cus_A` has a separate EUR available credit of 5000 and USD available credit of 11000

### Requirement: Entry effect on balance
Balances SHALL use the customer's perspective. A `CREDIT` entry SHALL add its amount to the credit total of its bucket, and a `DEBIT` entry SHALL add its amount to the debit total. Debits and credits on the same bucket SHALL NOT be netted against each other, including within one posting.

#### Scenario: Hold moves money between buckets
- **GIVEN** `cus_A` EUR available credit 10000, available debit 0, held credit 0, held debit 0
- **WHEN** a posting debits `cus_A` `AVAILABLE` 5000 and credits `cus_A` `HELD` 5000
- **THEN** available debit is 5000, available credit is 10000, held credit is 5000 and held debit is 0, so available is 5000 and held is 5000

#### Scenario: FX on one customer changes two balances
- **GIVEN** `cus_A` EUR available credit 45000 and USD available credit 2000, all other totals 0
- **WHEN** a posting debits `cus_A` `AVAILABLE` 10000 EUR, credits `int_FX_POSITION_EUR` 10000 EUR, debits `int_FX_POSITION_USD` 11000 USD and credits `cus_A` `AVAILABLE` 11000 USD
- **THEN** `cus_A` EUR available debit is 10000 and available credit 45000, and USD available credit is 13000 and available debit 0

#### Scenario: Debit and credit on one bucket in one posting are kept apart
- **GIVEN** `cus_A` EUR has all totals 0
- **WHEN** one posting credits `cus_A` `AVAILABLE` 1000 and debits `cus_A` `AVAILABLE` 200
- **THEN** `cus_A` EUR available credit is 1000 and available debit is 200

### Requirement: Balances may go negative
The journal SHALL NOT reject a posting because of a resulting balance. A bucket's balance (credit total minus debit total) MAY become negative. The debit and credit totals themselves SHALL never be negative.

#### Scenario: Overdraw recorded
- **GIVEN** `cus_A` EUR available credit 1000 and available debit 0
- **WHEN** a balanced posting debits `cus_A` `AVAILABLE` 5000
- **THEN** it is recorded, available debit is 5000 and available credit 1000, so available is -4000

### Requirement: Balance records the posting that changed it
After a posting is recorded, every customer balance it changed SHALL hold that posting's `id` as its last transaction id, together with the new `version` and its four debit and credit totals. A balance the posting did not change SHALL keep its previous last transaction id.

#### Scenario: FX posting stamps both balances
- **WHEN** the FX posting from "FX on one customer changes two balances" is recorded with id `T1`
- **THEN** `cus_A` EUR and `cus_A` USD both have last transaction id `T1`

#### Scenario: Untouched balance keeps its last posting
- **GIVEN** `cus_A` EUR has last transaction id `T1`
- **WHEN** a posting `T2` touching only `cus_B` is recorded
- **THEN** `cus_A` EUR still has last transaction id `T1`

### Requirement: Balances are derivable from the log
For every customer account, bucket and currency, the maintained debit total SHALL equal the sum of the amounts of all recorded `DEBIT` entries for that key, and the maintained credit total SHALL equal the sum of the amounts of all recorded `CREDIT` entries for that key.

#### Scenario: Reconstruction matches
- **WHEN** debit and credit totals are recomputed from all recorded postings
- **THEN** they equal the maintained totals
