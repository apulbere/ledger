# Transaction search

Behaviour: [transaction-search](../../../openspec/specs/query/transaction-search/spec.md). Contract: [OpenAPI](../query-app/src/main/openapi/openapi.yaml). Tables: [data model](data-model.md).

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant Q as query
    participant DB as Postgres
    C->>Q: search by account, optional filters and cursor
    Q->>Q: check each parameter, decode cursor
    alt invalid
        Q-->>C: 400 problem naming the parameter
    end
    Q->>DB: account index range scan, newest occurrence first, after the cursor, one row more than the limit
    Note over Q,DB: ordered by occurrence, the field clients filter on: posting time is display only and ids are not ordered within a millisecond
    Note over Q,DB: cursor is the last item's occurrence time and id: pages stay stable while postings arrive
    Note over Q,DB: a posting ingested behind the cursor during a walk is not seen by that walk
    Q-->>C: 200 page, next cursor only if the extra row existed
```
