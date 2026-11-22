# Data model

Behaviour: [account-balances](../../../openspec/specs/journal/account-balances/spec.md), [transaction-posting](../../../openspec/specs/journal/transaction-posting/spec.md). Tables: [changelog](../journal-app/src/main/resources/db/changelog/db.changelog-master.yaml).

```mermaid
erDiagram
    TRANSACTION {
        uuid id PK "UUIDv7, not ordered within a millisecond: order by balance version"
        text idempotency_key UK
        text reference_id
        timestamptz occurred_at
        timestamptz posted_at "display only, not commit order"
        text description
        jsonb metadata
        jsonb entries "on the row so one posting is one change event"
        jsonb versions
    }
    BALANCE {
        text account_id PK "customer only: internal accounts never locked"
        char currency PK
        bigint available_debit "buckets and directions as columns: a hold takes one row lock"
        bigint available_credit
        bigint held_debit
        bigint held_credit
        bigint version
        uuid last_transaction_id
    }
    TRANSACTION ||..o{ BALANCE : "customer entries change"
```
