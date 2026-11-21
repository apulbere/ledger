package ledger.journal.posting;

import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.node.ObjectNode;

public record PostingRequest(
        String referenceId,
        OffsetDateTime occurredAt,
        String description,
        ObjectNode metadata,
        List<EntryRequest> entries) {

    // bucket is a string so an unknown value is a domain error (INVALID_BUCKET), not a parse error.
    public record EntryRequest(
            String accountId,
            @JsonDeserialize(using = OmittedNotNull.class) String bucket,
            Direction direction,
            Long amount,
            String currency) {
    }

    /**
     * An omitted bucket is fine (internal entries have none); an explicit {@code null} is malformed.
     * Jackson asks for the null value only on a JSON null and for the absent value only when the property is missing,
     * which a plain annotation can't tell apart on a record.
     */
    static final class OmittedNotNull extends ValueDeserializer<String> {

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) {
            return ctxt.readValue(p, String.class);
        }

        @Override
        public String getNullValue(DeserializationContext ctxt) {
            return ctxt.reportInputMismatch(this, "bucket must not be null; omit it for internal accounts");
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }
}
