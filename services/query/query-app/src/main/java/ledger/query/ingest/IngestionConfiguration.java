package ledger.query.ingest;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
class IngestionConfiguration {

    /**
     * Never skip a record: a failure (undecodable bytes, unknown schema, database down) seeks back and retries the
     * same record forever, so its partition stops instead of losing a posting or balance version.
     */
    @Bean
    DefaultErrorHandler neverSkipErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(60_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        // Drop the default "not retryable" list (deserialization, conversion, ...): everything is retried.
        handler.setClassifications(Map.of(), true);
        return handler;
    }
}
