# Containers

Deployment: [docker-compose.yml](../../docker-compose.yml). Components: [journal](../../services/journal/docs/data-model.md), [journal change events](../../services/journal/docs/change-events.md), [query](../../services/query/docs/data-model.md), [query ingestion](../../services/query/docs/ingestion.md).

```mermaid
flowchart LR
    upstream([Upstream systems])
    readers([Read clients])
    subgraph ledger
        gateway[gateway<br/>one origin: Swagger UI + each service's API]
        swagger[Swagger UI<br/>every service's contract]
        journal[journal<br/>records postings, owns balances]
        query[query<br/>projections, transaction search]
        journaldb[(journal Postgres)]
        querydb[(query Postgres)]
        connect[Kafka Connect<br/>Debezium]
        kafka[[Kafka]]
        registry[Schema Registry]
    end
    upstream -- "REST" --> gateway
    readers -- "REST" --> gateway
    gateway --> swagger
    gateway -- "/journal" --> journal
    gateway -- "/query" --> query
    journal --> journaldb
    connect -- "logical replication" --> journaldb
    connect -- "posting and balance events,<br/>internal topics" --> kafka
    connect -- "Avro schemas" --> registry
    registry -- "schemas topic" --> kafka
    query -- "consumes posting and balance events" --> kafka
    query -- "writer schemas by id" --> registry
    query --> querydb
    note["journal never talks to Kafka:<br/>a posting never waits on CDC"]
    note -.- journal
    note2["query is eventually consistent with journal:<br/>it only learns of postings through Kafka"]
    note2 -.- query
```
