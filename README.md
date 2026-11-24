# ledger

Double-entry ledger. `journal` (write side) and `query` (read side), connected by Debezium CDC over Kafka.

Requires JDK 27 and Docker (the tests use Testcontainers).

## Build

```sh
./gradlew build                          # everything (composite build)
cd services/journal && ./gradlew build   # one service
```

## Run

```sh
docker compose up --build   # Postgres, Kafka, Schema Registry, Kafka Connect, both apps
docker compose down -v      # stop and wipe all data
```

| Service          | Host port |
|------------------|-----------|
| journal-app      | 8080      |
| query-app        | 8081      |
| journal-postgres | 5432      |
| query-postgres   | 5433      |
| kafka            | 9092      |
| schema-registry  | 8085      |
| kafka-connect    | 8083      |
| gateway          | 8090      |

On startup the `connector-init` one-shot registers the Debezium connector, which streams journal postings and
customer balances to the `ledger.journal.transaction` and `ledger.journal.balance` topics (Avro contract in
[journal-events](services/journal/journal-events/src/main/avro/)). Check it with
`curl localhost:8083/connectors/journal-cdc/status`. Debezium's read grants come from the Postgres init script,
which only runs on an empty volume: a volume created before the connector existed needs `docker compose down -v`.

The gateway at http://localhost:8090 serves Swagger UI with every service's hand-written OpenAPI contract,
and each service's API under its prefix (`/journal/...`, `/query/...`), so "Try it out" works from the browser.

`query` consumes both topics from the start and searches postings by account, a few moments after they are recorded:

```sh
curl 'localhost:8090/query/accounts/cus_01jcz8k2m4f7r9t3vbx5w6y8za/transactions?currency=EUR&limit=10'
```

Its projections are rebuilt by replaying the topics: to rebuild them after wiping only query's database, also start
from a new consumer group (`KAFKA_GROUP_ID`, default `query`), since the old group would resume where it stopped.

Docs: [docs/README.md](docs/README.md). Changes are planned with OpenSpec in `openspec/`.
