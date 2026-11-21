package ledger.journal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@SpringBootApplication
public class JournalApplication {

    public static void main(String[] args) {
        SpringApplication.run(JournalApplication.class, args);
    }

    /**
     * Strict request parsing, completing the {@code spring.jackson} settings: a number or boolean is never coerced
     * into a text field, so it is a 400 rather than a later domain error. Boot has no property for this.
     */
    @Bean
    JsonMapperBuilderCustomizer noScalarToTextCoercion() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
