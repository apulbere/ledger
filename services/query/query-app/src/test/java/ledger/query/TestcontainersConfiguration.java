package ledger.query;

import ledger.query.events.JournalEvents;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Shared by every integration test: one set of containers per cached context.
// Kafka mirrors docker-compose: topic auto-creation is off, so tests create the topics, as the connector does.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String SCHEMA_REGISTRY = "schemaRegistry";

    @Bean
    Network network() {
        return Network.newNetwork();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18").withCommand("postgres", "-c", "fsync=off");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka(Network network) {
        return new KafkaContainer("apache/kafka:4.3.2")
                .withListener("kafka:19092")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withNetwork(network)
                .withNetworkAliases("kafka");
    }

    @Bean(SCHEMA_REGISTRY)
    GenericContainer<?> schemaRegistry(Network network, KafkaContainer kafka) {
        return new GenericContainer<>("confluentinc/cp-schema-registry:8.3.3")
                .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
                .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                .withExposedPorts(8081)
                .waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
                .withNetwork(network)
                .withNetworkAliases("schema-registry")
                .dependsOn(kafka);
    }

    @Bean
    DynamicPropertyRegistrar schemaRegistryUrl(@Qualifier(SCHEMA_REGISTRY) GenericContainer<?> schemaRegistry) {
        return registry -> registry.add("ledger.schema-registry.url",
                () -> "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081));
    }

    @Bean
    JournalEvents journalEvents(KafkaContainer kafka, @Qualifier(SCHEMA_REGISTRY) GenericContainer<?> schemaRegistry) {
        return new JournalEvents(kafka.getBootstrapServers(),
                "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081));
    }
}
