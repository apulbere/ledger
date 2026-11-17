# ledger

Double-entry ledger. `journal` (write side) and `query` (read side), connected by Debezium CDC over Kafka.

Requires JDK 25 and Docker (the tests use Testcontainers).

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

| Service         | Host port |
|-----------------|-----------|
| journal-app     | 8080      |
| query-app       | 8081      |
| postgres        | 5432      |
| kafka           | 9092      |
| schema-registry | 8085      |
| kafka-connect   | 8083      |

Docs: [docs/README.md](docs/README.md). Changes are planned with OpenSpec in `openspec/`.
