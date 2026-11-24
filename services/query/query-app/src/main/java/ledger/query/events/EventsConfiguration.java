package ledger.query.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EventsConfiguration {

    @Bean
    SchemaRegistryClient schemaRegistryClient(@Value("${ledger.schema-registry.url}") String url) {
        return new SchemaRegistryClient(url);
    }

    @Bean
    EventDecoder eventDecoder(SchemaRegistryClient schemaRegistry) {
        return new EventDecoder(schemaRegistry::schema);
    }
}
