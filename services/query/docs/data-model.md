# Data model

Behaviour: [transaction-search](../../../openspec/specs/query/transaction-search/spec.md), [account-balances](../../../openspec/specs/query/account-balances/spec.md). Tables: [changelog](../query-app/src/main/resources/db/changelog/db.changelog-master.yaml). Source events: [Avro](../../journal/journal-events/src/main/avro/).

```mermaid
erDiagram
    TRANSACTION {
        uuid id PK "posting id; a redelivered posting hits it and is ignored"
        text reference_id
        timestamptz occurred_at
        timestamptz posted_at
        text description
        jsonb metadata
        jsonb entries "event text as is: the journal's REST shape, all accounts"
        jsonb versions
    }
    ACCOUNT_TRANSACTION {
        text account_id PK "customer and internal accounts"
        uuid transaction_id PK,FK
        timestamptz occurred_at "copied from the posting: order, range and cursor in one index"
        char_3_array currencies "this account's currencies in the posting: one row per posting keeps unfiltered search distinct-free"
    }
    BALANCE_VERSION {
        text account_id PK "customer accounts only"
        char currency PK
        bigint version PK "every version kept; current balance is the highest"
        bigint available_debit
        bigint available_credit
        bigint held_debit
        bigint held_credit
        uuid last_transaction_id "indexed: balances right after a posting; no FK, the two topics are not ordered"
    }
    TRANSACTION ||--|{ ACCOUNT_TRANSACTION : "one row per distinct account in entries"
    TRANSACTION ||..o{ BALANCE_VERSION : "produced"
```
