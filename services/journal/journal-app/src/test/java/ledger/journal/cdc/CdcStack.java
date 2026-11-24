package ledger.journal.cdc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The CDC path as docker-compose runs it: journal Postgres with the real init script, Kafka, Schema Registry and
 * Kafka Connect built from the real Dockerfile, registered with the real connector config. Started once per JVM.
 */
final class CdcStack {

    static final String CONNECTOR = "journal-cdc";

    private static final Path DOCKER = Path.of("../../../docker").toAbsolutePath().normalize();
    private static final Network NETWORK = Network.newNetwork();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("journal")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "fsync=off")
            .withCopyFileToContainer(MountableFile.forHostPath(DOCKER.resolve("postgres/journal-init.sql")),
                    "/docker-entrypoint-initdb.d/init.sql")
            .withNetwork(NETWORK)
            .withNetworkAliases("journal-postgres");

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2")
            .withListener("kafka:19092")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka");

    static final GenericContainer<?> SCHEMA_REGISTRY = new GenericContainer<>("confluentinc/cp-schema-registry:8.3.3")
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .withExposedPorts(8081)
            .waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
            .withNetwork(NETWORK)
            .withNetworkAliases("schema-registry")
            .dependsOn(KAFKA);

    static final GenericContainer<?> CONNECT = new GenericContainer<>(
            new ImageFromDockerfile("ledger-kafka-connect-test", false)
                    .withFileFromPath(".", DOCKER.resolve("kafka-connect")))
            .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
            .withEnv("GROUP_ID", "ledger-connect")
            .withEnv("CONFIG_STORAGE_TOPIC", "connect_configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "connect_offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "connect_statuses")
            .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_OFFSET_FLUSH_INTERVAL_MS", "1000")
            .withEnv("KEY_CONVERTER", "io.confluent.connect.avro.AvroConverter")
            .withEnv("VALUE_CONVERTER", "io.confluent.connect.avro.AvroConverter")
            .withEnv("CONNECT_KEY_CONVERTER_SCHEMA_REGISTRY_URL", "http://schema-registry:8081")
            .withEnv("CONNECT_VALUE_CONVERTER_SCHEMA_REGISTRY_URL", "http://schema-registry:8081")
            .withExposedPorts(8083)
            .waitingFor(Wait.forHttp("/connectors").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
            .withNetwork(NETWORK)
            .dependsOn(KAFKA, SCHEMA_REGISTRY, POSTGRES);

    private static boolean started;

    private CdcStack() {
    }

    static synchronized void start() {
        if (!started) {
            POSTGRES.start();
            KAFKA.start();
            SCHEMA_REGISTRY.start();
            CONNECT.start();
            started = true;
        }
    }

    static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
    }

    private static String connectUrl() {
        return "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083);
    }

    /** Registers the connector from the compose config file, as connector-init does, and waits for it to run. */
    static void registerConnector() throws Exception {
        ObjectNode config = (ObjectNode) JSON.readTree(
                Files.readString(DOCKER.resolve("kafka-connect/connectors/journal-cdc.json")));
        send("PUT", "/connectors/" + CONNECTOR + "/config", JSON.writeValueAsString(config));
        awaitState("RUNNING");
    }

    /** Stops the connector's task, as an outage would, keeping its offsets and replication slot. */
    static void stopConnector() throws Exception {
        send("PUT", "/connectors/" + CONNECTOR + "/stop", null);
        awaitState("STOPPED");
    }

    static void resumeConnector() throws Exception {
        send("PUT", "/connectors/" + CONNECTOR + "/resume", null);
        awaitState("RUNNING");
    }

    static String connectorState() throws Exception {
        String body = send("GET", "/connectors/" + CONNECTOR + "/status", null);
        var status = JSON.readTree(body);
        String connector = status.get("connector").get("state").asString();
        var tasks = status.get("tasks");
        if (!"RUNNING".equals(connector) || tasks.isEmpty()) {
            return connector;
        }
        return tasks.get(0).get("state").asString();
    }

    private static void awaitState(String state) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        String current = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                current = connectorState();
                if (state.equals(current)) {
                    return;
                }
            } catch (IllegalStateException notYetKnown) {
                // 404 right after registration
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Connector not " + state + ", last state " + current + "\n" + CONNECT.getLogs());
    }

    private static String send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(connectUrl() + path))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + path + ": " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }
}
