# Proposal

## Why

Each customer balance keeps only a net `available` and a net `held` figure, so how much was debited and how much was credited on each bucket is lost on the balance row and can only be recovered by replaying the log. Keeping the two sides apart preserves that information where the balance is maintained, at the same cost per posting.

## What Changes

- The customer balance holds four running totals instead of two nets: `available_debit`, `available_credit`, `held_debit`, `held_credit`, each the sum of the amounts of entries with that bucket and direction, in minor units.
- The net of a bucket is no longer stored; it is its credit total minus its debit total (customer perspective, unchanged).
- Debits and credits on the same bucket within one posting are added to their own totals and no longer netted against each other.
- Totals are never negative; a bucket's net may still go negative, as today.
- A migration replaces the `available` and `held` columns of `journal.balance` with the four totals, backfilled from the posting log.
- No REST or event contract change: balances are not exposed by the API, and postings and their `versions` are unchanged.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `journal/account-balances`: a balance holds debit and credit totals per bucket instead of a net per bucket; the entry effect, negative-balance, last-posting and log-derivation requirements are restated in those terms.

## Impact

- `journal.balance` schema (new Liquibase changeset with backfill), the balance upsert and the per-posting delta in the journal write path.
- Tests that read `available`/`held` (schema, integration, concurrency, reconstruction).
- No change to `openapi.yaml`, `TransactionPosted.avsc`, or other services.

## Docs impact

- System: none, no container or connection changes.
- Component: update `services/journal/docs/data-model.md` (BALANCE columns).
