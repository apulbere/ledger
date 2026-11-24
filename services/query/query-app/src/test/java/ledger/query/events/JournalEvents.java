package ledger.query.events;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import ledger.journal.events.BalanceChanged;
import ledger.journal.events.BalanceKey;
import ledger.journal.events.TransactionKey;
import ledger.journal.events.TransactionPosted;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes journal change events the way the connector does: topics created up front (broker auto-create is off),
 * the {@code journal-events} schemas registered under the topic subjects, records in Confluent wire format.
 */
public final class JournalEvents implements AutoCloseable {

    public static final String TRANSACTION_TOPIC = "ledger.journal.transaction";
    public static final String BALANCE_TOPIC = "ledger.journal.balance";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String bootstrapServers;
    private final String schemaRegistryUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Integer> schemaIds = new ConcurrentHashMap<>();
    private KafkaProducer<byte[], byte[]> producer;

    public JournalEvents(String bootstrapServers, String schemaRegistryUrl) {
        this.bootstrapServers = bootstrapServers;
        this.schemaRegistryUrl = schemaRegistryUrl;
    }

    public void posting(TransactionPosted posting) {
        send(TRANSACTION_TOPIC,
                encode(TRANSACTION_TOPIC + "-key", TransactionKey.newBuilder().setId(posting.getId()).build()),
                encode(TRANSACTION_TOPIC + "-value", posting));
    }

    public void balance(BalanceChanged balance) {
        BalanceKey key = BalanceKey.newBuilder()
                .setAccountId(balance.getAccountId()).setCurrency(balance.getCurrency()).build();
        send(BALANCE_TOPIC, encode(BALANCE_TOPIC + "-key", key), encode(BALANCE_TOPIC + "-value", balance));
    }

    /** A record exactly as given, e.g. one that cannot be decoded. Returns its offset. */
    public long raw(String topic, byte[] key, byte[] value) {
        return send(topic, key, value);
    }

    /** Registers the record's schema under the subject (idempotent in Schema Registry) and encodes it. */
    public byte[] encode(String subject, SpecificRecord record) {
        int id = schemaIds.computeIfAbsent(subject + "|" + record.getSchema().getFullName(),
                ignored -> register(subject, record.getSchema()));
        return WireFormat.encode(id, record);
    }

    public String bootstrapServers() {
        return bootstrapServers;
    }

    private synchronized long send(String topic, byte[] key, byte[] value) {
        if (producer == null) {
            createTopics();
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            producer = new KafkaProducer<>(props, new ByteArraySerializer(), new ByteArraySerializer());
        }
        try {
            return producer.send(new ProducerRecord<>(topic, key, value)).get().offset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void createTopics() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            for (String topic : List.of(TRANSACTION_TOPIC, BALANCE_TOPIC)) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException(e.getCause());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int register(String subject, Schema schema) {
        try {
            String body = JSON.writeValueAsString(Map.of("schema", schema.toString()));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(schemaRegistryUrl + "/subjects/" + subject + "/versions"))
                            .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Register " + subject + ": " + response.statusCode() + " "
                        + response.body());
            }
            return JSON.readTree(response.body()).get("id").asInt();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() {
        if (producer != null) {
            producer.close();
        }
    }
}
