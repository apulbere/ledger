# Ingestion

Behaviour: [transaction-search](../../../openspec/specs/query/transaction-search/spec.md), [account-balances](../../../openspec/specs/query/account-balances/spec.md). Events: [change-events](../../../openspec/specs/journal/change-events/spec.md), [Avro](../../journal/journal-events/src/main/avro/). Settings: [application.yaml](../query-app/src/main/resources/application.yaml). Tables: [data model](data-model.md).

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka
    participant Q as query
    participant SR as Schema Registry
    participant DB as Postgres
    Note over K,Q: one consumer group for both topics, from the earliest offset: a new group rebuilds everything
    K->>Q: record, key and value as bytes
    Q->>SR: writer schema by id, first time only
    Q->>Q: decode against the journal-events contract
    Note over Q: decoded in the listener, not by a Kafka deserializer: those failures would be skipped
    alt posting
        Q->>DB: begin, insert posting unless its id is held
        opt inserted
            Q->>DB: one account row per distinct account
        end
        Q->>DB: commit
    else balance
        Q->>DB: insert version unless held
    end
    Q->>K: commit offset, after the database
    Note over Q,K: crash before the offset commit means redelivery, absorbed by the "unless held" writes
    Note over K,Q: any failure: seek back and retry the same record with backoff up to a minute, forever, never skip
```
