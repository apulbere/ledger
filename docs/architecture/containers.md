# Containers

Deployment: [docker-compose.yml](../../docker-compose.yml). Components: [journal](../../services/journal/docs/data-model.md), [journal change events](../../services/journal/docs/change-events.md), [query](../../services/query/docs/data-model.md), [query ingestion](../../services/query/docs/ingestion.md).

```mermaid
flowchart LR
    upstream([Upstream systems])
    readers([Read clients])
    subgraph ledger
        gateway[API gateway<br/>routes each service's API by prefix]
        journal[journal<br/>records postings and customer balances]
        query[query<br/>projections, transaction search]
        journaldb[(journal Postgres)]
        querydb[(query Postgres)]
        connect[Kafka Connect<br/>Debezium]
        kafka[[Kafka]]
        registry[Schema Registry]
    end
    upstream -- "REST" --> gateway
    readers -- "REST" --> gateway
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
```
