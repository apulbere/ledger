# Posting

Behaviour: [transaction-posting](../../../openspec/specs/journal/transaction-posting/spec.md). Contract: [OpenAPI](../journal-app/src/main/openapi/openapi.yaml). Tables: [data model](data-model.md).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant J as journal
    participant DB as Postgres
    C->>J: posting + Idempotency-Key
    J->>J: parse and validate
    alt invalid
        J-->>C: 400 / 422 problem
    end
    J->>J: net change per customer balance, sorted by account then currency
    Note over J,DB: same lock order for every posting, so no deadlocks
    J->>DB: begin
    opt customer entries
        J->>DB: batch upsert balances in sorted order, row locks held
        Note over DB: driver batch rewriting off, a multi-row upsert has no lock order
    end
    J->>DB: append posting
    Note over J,DB: no lookup before writing, the unique key is the idempotency check
    alt idempotency key already recorded
        DB-->>J: unique violation
        J->>DB: rollback
        J-->>C: 409 problem
    else
        J->>DB: commit, locks released
        J-->>C: 201 posting
    end
```
