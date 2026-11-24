package ledger.query.search;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keyset position after the last item of a page: its {@code occurredAt} and id, as base64url JSON. Opaque to
 * clients and unsigned: a forged but well-formed cursor only moves where the next page starts.
 */
record Cursor(Instant occurredAt, UUID id) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    String encode() {
        String json = JSON.writeValueAsString(Map.of("o", occurredAt.toString(), "i", id.toString()));
        return ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String text) {
        try {
            JsonNode node = JSON.readTree(new String(DECODER.decode(text), StandardCharsets.UTF_8));
            return new Cursor(Instant.parse(node.required("o").asString()),
                    UUID.fromString(node.required("i").asString()));
        } catch (RuntimeException e) {
            throw new InvalidParameterException("cursor", "cursor was not issued by this API");
        }
    }
}
