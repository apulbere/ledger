# Containers

Deployment: [docker-compose.yml](../../docker-compose.yml). Components: [journal](../../services/journal/docs/data-model.md), [journal change events](../../services/journal/docs/change-events.md).

```mermaid
flowchart LR
    upstream([Upstream systems])
    subgraph ledger
        journal[journal<br/>records postings, owns balances]
        query[query]
        journaldb[(journal Postgres)]
        querydb[(query Postgres)]
        connect[Kafka Connect<br/>Debezium]
        kafka[[Kafka]]
        registry[Schema Registry]
    end
    upstream -- "REST" --> journal
    journal --> journaldb
    query --> querydb
    connect -- "logical replication" --> journaldb
    connect -- "posting and balance events,<br/>internal topics" --> kafka
    connect -- "Avro schemas" --> registry
    registry -- "schemas topic" --> kafka
    note["journal never talks to Kafka:<br/>a posting never waits on CDC"]
    note -.- journal
```
