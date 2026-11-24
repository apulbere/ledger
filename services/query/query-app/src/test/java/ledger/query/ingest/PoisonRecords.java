package ledger.query.ingest;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * The operator's way out of a blocked partition: stop the consumers, move the group past the bad record, start
 * again. Used by tests to clean up after proving that ingestion blocks.
 */
final class PoisonRecords {

    private PoisonRecords() {
    }

    static void skip(KafkaListenerEndpointRegistry listeners, String bootstrapServers, String groupId,
            String topic, long poisonOffset) {
        listeners.stop();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.alterConsumerGroupOffsets(groupId,
                    Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(poisonOffset + 1))).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            listeners.start();
        }
    }
}
