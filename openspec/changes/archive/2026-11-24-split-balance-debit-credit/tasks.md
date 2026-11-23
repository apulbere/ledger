# Tasks

## 1. Schema (design D3)

- [x] 1.1 Add new Liquibase changeset(s) to `db.changelog-master.yaml` that add `available_debit`, `available_credit`, `held_debit`, `held_credit` (`bigint`, default 0), backfill them from customer entries in `journal.transaction.entries`, raise an error if any row's `credit - debit` differs from its old `available` / `held`, then drop the defaults, set `NOT NULL`, and drop `available` and `held`; include a rollback block restoring the nets. Leave `create-table-balance` unchanged. Verify `SchemaTest` (updated to expect the four columns in order after `currency`) passes
- [x] 1.2 Add a migration test: on a database migrated up to the previous changeset, record postings (including a hold, a release and an overdraw), run the new changeset, and verify each row's totals equal the per-direction sums from the log; and that a row whose stored net was tampered with makes the migration fail with the old columns still present

## 2. Write path (spec: journal/account-balances)

- [x] 2.1 Change the per-posting balance delta to four sums per customer `(account, currency)`, still sorted in lock order and skipping internal entries. Verify `BalanceDeltaTest` covers the hold, FX, credit-and-debit-on-one-bucket (credit 1000 and debit 200 stay 1000 / 200) and internal-only cases
- [x] 2.2 Update the balance upsert to insert and add the four totals, keeping `version + 1` and `last_transaction_id`. Verify with `PostingIntegrationTest` updated to assert totals for every account-balances scenario: first posting, independent currencies, hold, FX, one-bucket credit and debit, overdraw (available debit 5000, credit 1000), and last transaction id stamping
- [x] 2.3 Update the test support balance read to return the four totals, and adjust `PostingConcurrencyTest` to assert `available_credit` (and version) after concurrent postings. Verify the concurrency tests pass unchanged in intent
- [x] 2.4 Update `BalanceReconstructionTest` to recompute debit and credit totals per account, currency and bucket from the log and compare with the maintained totals. Verify with a green run

## 3. Integration check

- [x] 3.1 Run `./gradlew build` at the repo root and `docker compose up --build` on a database that already holds postings from the previous version, `curl` a posting, then query `journal.balance`. Verify the migration ran, the totals match the log and the new posting added to the right totals

## 4. Docs

- [x] 4.1 Update `services/journal/docs/data-model.md` so the BALANCE entity shows the four totals (starting from the design.md ER diagram, written from the implemented migration), and check `docs/README.md` index entries and the container diagram in `docs/architecture/containers.md` are still accurate. Verify the Mermaid renders and the links work
