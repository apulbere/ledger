# Change events

Behaviour: [change-events](../../../openspec/specs/journal/change-events/spec.md). Contract: [Avro](../journal-events/src/main/avro/). Connector: [config](../../../docker/kafka-connect/connectors/journal-cdc.json). Tables: [data model](data-model.md).

```mermaid
sequenceDiagram
    autonumber
    participant J as journal
    participant DB as Postgres
    participant D as Debezium
    participant SR as Schema Registry
    participant K as Kafka
    J->>DB: commit posting and balance upserts
    Note over J,DB: journal owns the publication: only a table owner may publish a table
    D->>DB: read WAL through the slot, publication tables only
    D->>D: row state only, schemas renamed to the contract names
    D->>SR: register or look up key and value schemas
    D->>K: balance events keyed by account and currency
    D->>K: posting event keyed by id
    Note over D,K: Connect creates the topics with infinite retention, broker auto-create is off
    D->>DB: confirm position after Kafka acknowledged
    Note over D,DB: unconfirmed WAL is kept and resent after a restart: at least once
```
