package ledger.journal.cdc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a topic from the beginning and decodes the Confluent wire format (magic byte 0, 4-byte schema id, Avro
 * body) with the writer schema from Schema Registry, without the Confluent client libraries.
 */
final class ChangeEvents {

    record Event(Schema keySchema, GenericRecord key, Schema valueSchema, GenericRecord value) {

        String key(String field) {
            return String.valueOf(key.get(field));
        }

        Object value(String field) {
            Object v = value.get(field);
            return v instanceof CharSequence s ? s.toString() : v;
        }
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<Integer, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private ChangeEvents() {
    }

    /** Every event on the topic from the earliest offset, until one matches {@code until} or the timeout. */
    static List<Event> readUntil(String topic, Predicate<Event> until) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CdcStack.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cdc-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<Event> events = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(props, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
            List<TopicPartition> partitions = List.of();
            while (partitions.isEmpty() && Instant.now().isBefore(deadline)) {
                partitions = consumer.partitionsFor(topic, Duration.ofSeconds(5)).stream()
                        .map(p -> new TopicPartition(topic, p.partition()))
                        .toList();
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    Event event = decode(record);
                    events.add(event);
                    if (until.test(event)) {
                        return events;
                    }
                }
            }
        }
        throw new AssertionError("No matching event on " + topic + " within 60s; read " + events.size());
    }

    private static Event decode(ConsumerRecord<byte[], byte[]> record) {
        Schema keySchema = writerSchema(record.key());
        Schema valueSchema = writerSchema(record.value());
        return new Event(keySchema, read(keySchema, record.key()), valueSchema, read(valueSchema, record.value()));
    }

    private static Schema writerSchema(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.get() != 0) {
            throw new IllegalStateException("Not Confluent wire format");
        }
        return SCHEMAS.computeIfAbsent(buffer.getInt(), ChangeEvents::fetchSchema);
    }

    private static GenericRecord read(Schema schema, byte[] bytes) {
        try {
            return new GenericDatumReader<GenericRecord>(schema)
                    .read(null, DecoderFactory.get().binaryDecoder(bytes, 5, bytes.length - 5, null));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Schema fetchSchema(int id) {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(CdcStack.schemaRegistryUrl() + "/schemas/ids/" + id)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Schema.Parser().parse(JSON.readTree(response.body()).get("schema").asString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
