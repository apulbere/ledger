package ledger.journal.posting;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import ledger.journal.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Posts over real HTTP against the app and a Testcontainers Postgres, and reads the tables back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
abstract class PostingApiSupport {

    private static final String TYPEID_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcClient jdbc;

    record Response(int status, JsonNode body) {
    }

    record Balance(long availableDebit, long availableCredit, long heldDebit, long heldCredit,
            long version, UUID lastTransactionId) {

        long available() {
            return availableCredit - availableDebit;
        }

        long held() {
            return heldCredit - heldDebit;
        }
    }

    /** A fresh customer account id, so tests sharing the database never touch each other's balances. */
    static String newCustomer() {
        StringBuilder id = new StringBuilder("cus_0");
        for (int i = 0; i < 25; i++) {
            id.append(TYPEID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(TYPEID_ALPHABET.length())));
        }
        return id.toString();
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    Response post(String idempotencyKey, PostingRequest request) {
        return post(idempotencyKey, nonNullWriter().writeValueAsString(request));
    }

    // Fixtures use null for "absent"; a client omits those fields (an explicit null bucket is malformed).
    private JsonMapper nonNullWriter() {
        return jsonMapper.rebuild()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    Response post(String idempotencyKey, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            OpenApiContract.check("POST", "/transactions",
                    Map.of("Content-Type", "application/json", "Idempotency-Key", idempotencyKey), body,
                    response.statusCode(), response.headers().firstValue("Content-Type").orElse(null), response.body());
            return new Response(response.statusCode(), jsonMapper.readTree(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Posts and asserts 201, returning the recorded posting. */
    JsonNode record(PostingRequest request) {
        Response response = post(newKey(), request);
        if (response.status() != 201) {
            throw new AssertionError("Expected 201, got " + response.status() + ": " + response.body());
        }
        return response.body();
    }

    Optional<Balance> balance(String accountId, String currency) {
        return jdbc.sql("""
                SELECT available_debit, available_credit, held_debit, held_credit, version, last_transaction_id
                FROM journal.balance WHERE account_id = ? AND currency = ?
                """).params(accountId, currency)
                .query((rs, n) -> new Balance(rs.getLong("available_debit"), rs.getLong("available_credit"),
                        rs.getLong("held_debit"), rs.getLong("held_credit"), rs.getLong("version"),
                        rs.getObject("last_transaction_id", UUID.class)))
                .optional();
    }

    long postingsWithKey(String idempotencyKey) {
        return jdbc.sql("SELECT count(*) FROM journal.transaction WHERE idempotency_key = ?")
                .param(idempotencyKey).query(Long.class).single();
    }

    /** The versions recorded on every posting for one balance, in version order. */
    List<Long> recordedVersions(String accountId, String currency) {
        return jdbc.sql("""
                SELECT (v->>'version')::bigint
                FROM journal.transaction t, jsonb_array_elements(t.versions) v
                WHERE v->>'accountId' = ? AND v->>'currency' = ?
                ORDER BY 1
                """).params(accountId, currency).query(Long.class).list();
    }
}
