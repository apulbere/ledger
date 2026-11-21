# Containers

Deployment: [docker-compose.yml](../../docker-compose.yml). Components: [journal](../../services/journal/docs/data-model.md).

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
    connect -- "internal topics" --> kafka
    connect -- "Avro schemas" --> registry
    registry -- "schemas topic" --> kafka
```
