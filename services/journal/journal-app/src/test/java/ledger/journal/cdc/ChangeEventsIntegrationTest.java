package ledger.journal.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import ledger.journal.cdc.ChangeEvents.Event;
import ledger.journal.events.BalanceChanged;
import ledger.journal.events.BalanceKey;
import ledger.journal.events.TransactionKey;
import ledger.journal.events.TransactionPosted;
import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** journal/change-events: what the real connector publishes for postings recorded through the REST API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangeEventsIntegrationTest {

    private static final String TRANSACTIONS = "ledger.journal.transaction";
    private static final String BALANCES = "ledger.journal.balance";
    private static final String TYPEID_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    // Recorded before the connector existed: must arrive through the initial snapshot.
    private String preExistingCustomer;
    private String preExistingPosting;

    // journal connects as journal_user, as in compose, so the init script's default grants let Debezium read.
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        CdcStack.start();
        registry.add("spring.datasource.url", CdcStack.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "journal_user");
        registry.add("spring.datasource.password", () -> "journal_pass");
    }

    @BeforeAll
    void recordThenRegisterConnector() throws Exception {
        preExistingCustomer = newCustomer();
        preExistingPosting = id(record(credit(preExistingCustomer, 700, "EUR"), debit("int_BANK_NOSTRO_EUR", 700, "EUR")));
        CdcStack.registerConnector();
    }

    @Test
    void recordedPostingIsPublished() {
        String a = newCustomer();
        JsonNode recorded = record("""
                {"referenceId": "ref-%s", "description": "card", "metadata": {"source": "cdc-test"},
                 "entries": [%s, %s]}
                """.formatted(a, credit(a, 5000, "EUR"), debit("int_SETTLEMENT_VISA", 5000, "EUR")));
        String id = id(recorded);

        List<Event> events = ChangeEvents.readUntil(TRANSACTIONS, e -> e.key("id").equals(id));
        Event event = events.getLast();

        assertThat(events.stream().filter(e -> e.key("id").equals(id))).hasSize(1);
        assertThat(event.value("id")).isEqualTo(id);
        assertThat(event.value("reference_id")).isEqualTo("ref-" + a);
        assertThat(event.value("description")).isEqualTo("card");
        assertThat(event.value("idempotency_key")).isNotNull();
        assertThat(instant(event.value("occurred_at"))).isEqualTo(instant(recorded.get("occurredAt").asString()));
        assertThat(instant(event.value("posted_at"))).isEqualTo(instant(recorded.get("postedAt").asString()));
        assertThat(json.readTree((String) event.value("metadata"))).isEqualTo(recorded.get("metadata"));
        assertThat(json.readTree((String) event.value("entries"))).isEqualTo(recorded.get("entries"));
        assertThat(json.readTree((String) event.value("versions"))).isEqualTo(recorded.get("versions"));
    }

    @Test
    void postingWithoutOptionalFieldsHasNullsAndRfc3339Timestamps() {
        String a = newCustomer();
        String id = id(record(credit(a, 100, "EUR"), debit("int_BANK_NOSTRO_EUR", 100, "EUR")));

        Event event = ChangeEvents.readUntil(TRANSACTIONS, e -> e.key("id").equals(id)).getLast();

        assertThat(event.value("reference_id")).isNull();
        assertThat(event.value("description")).isNull();
        assertThat(event.value("metadata")).isNull();
        assertThat((String) event.value("occurred_at")).endsWith("Z");
        assertThat((String) event.value("posted_at")).endsWith("Z");
    }

    @Test
    void fxPostingPublishesTwoBalanceEvents() {
        String a = newCustomer();
        record(credit(a, 45000, "EUR"), debit("int_BANK_NOSTRO_EUR", 45000, "EUR"));
        record(credit(a, 2000, "USD"), debit("int_BANK_NOSTRO_USD", 2000, "USD"));
        String fx = id(record(
                debit(a, 10000, "EUR"), creditInternal("int_FX_POSITION_EUR", 10000, "EUR"),
                debit("int_FX_POSITION_USD", 11000, "USD"), credit(a, 11000, "USD")));

        List<Event> events = ChangeEvents.readUntil(BALANCES,
                e -> e.key("account_id").equals(a) && e.key("currency").equals("USD") && e.value("version").equals(2L));
        Map<String, Event> byCurrency = events.stream()
                .filter(e -> fx.equals(e.value("last_transaction_id")))
                .collect(Collectors.toMap(e -> e.key("currency"), e -> e));

        assertThat(byCurrency).containsOnlyKeys("EUR", "USD");
        assertThat(totals(byCurrency.get("EUR"))).containsExactly(a, "EUR", 2L, 10000L, 45000L, 0L, 0L);
        assertThat(totals(byCurrency.get("USD"))).containsExactly(a, "USD", 2L, 0L, 13000L, 0L, 0L);
    }

    @Test
    void internalOnlyPostingPublishesNoBalanceEvent() {
        String internal = id(record(debit("int_SETTLEMENT_VISA", 300, "EUR"), creditInternal("int_BANK_NOSTRO_EUR", 300, "EUR")));
        String sentinel = sentinel();

        assertThat(ChangeEvents.readUntil(TRANSACTIONS, e -> e.key("id").equals(internal))).isNotEmpty();
        assertThat(ChangeEvents.readUntil(BALANCES, e -> sentinel.equals(e.value("last_transaction_id"))))
                .noneMatch(e -> internal.equals(e.value("last_transaction_id")));
    }

    @Test
    void rejectedPostingPublishesNothing() {
        String unbalanced = newCustomer();
        String reused = newCustomer();
        String marker = "rejected-" + unbalanced;
        assertThat(post(newKey(), """
                {"referenceId": "%s", "entries": [%s, %s]}
                """.formatted(marker, credit(unbalanced, 5730, "EUR"), debit("int_BANK_NOSTRO_EUR", 5719, "EUR")))
                .statusCode()).isEqualTo(422);
        String key = newKey();
        assertThat(post(key, body(credit(reused, 100, "EUR"), debit("int_BANK_NOSTRO_EUR", 100, "EUR"))).statusCode())
                .isEqualTo(201);
        assertThat(post(key, body(credit(reused, 999, "EUR"), debit("int_BANK_NOSTRO_EUR", 999, "EUR"))).statusCode())
                .isEqualTo(409);
        String sentinel = sentinel();

        List<Event> postings = ChangeEvents.readUntil(TRANSACTIONS, e -> e.key("id").equals(sentinel));
        assertThat(postings).noneMatch(e -> marker.equals(e.value("reference_id")));
        assertThat(postings).filteredOn(e -> key.equals(e.value("idempotency_key"))).hasSize(1);

        List<Event> balances = ChangeEvents.readUntil(BALANCES, e -> sentinel.equals(e.value("last_transaction_id")));
        assertThat(balances).noneMatch(e -> e.key("account_id").equals(unbalanced));
        assertThat(balances).filteredOn(e -> e.key("account_id").equals(reused))
                .extracting(e -> e.value("available_credit")).containsExactly(100L);
    }

    @Test
    void balanceVersionsArriveInOrder() {
        String a = newCustomer();
        for (int i = 0; i < 3; i++) {
            record(credit(a, 100, "EUR"), debit("int_BANK_NOSTRO_EUR", 100, "EUR"));
        }

        List<Event> events = ChangeEvents.readUntil(BALANCES,
                e -> e.key("account_id").equals(a) && e.value("version").equals(3L));

        assertThat(events).filteredOn(e -> e.key("account_id").equals(a))
                .extracting(e -> e.value("version")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void existingDataIsPublished() {
        assertThat(ChangeEvents.readUntil(TRANSACTIONS, e -> e.key("id").equals(preExistingPosting))).isNotEmpty();
        Event balance = ChangeEvents.readUntil(BALANCES, e -> e.key("account_id").equals(preExistingCustomer)).getLast();
        assertThat(totals(balance)).containsExactly(preExistingCustomer, "EUR", 1L, 0L, 700L, 0L, 0L);
        assertThat(balance.value("last_transaction_id")).isEqualTo(preExistingPosting);
    }

    @Test
    void changesDuringAnOutageArePublishedAfterRecovery() throws Exception {
        String a = newCustomer();
        CdcStack.stopConnector();
        String id;
        try {
            id = id(record(credit(a, 4200, "EUR"), debit("int_BANK_NOSTRO_EUR", 4200, "EUR")));
        } finally {
            CdcStack.resumeConnector();
        }

        assertThat(ChangeEvents.readUntil(TRANSACTIONS, e -> e.key("id").equals(id))).isNotEmpty();
        assertThat(ChangeEvents.readUntil(BALANCES, e -> id.equals(e.value("last_transaction_id"))).getLast()
                .key("account_id")).isEqualTo(a);
    }

    @Test
    void topicsKeepEveryEventForever() throws Exception {
        sentinel();
        ChangeEvents.readUntil(TRANSACTIONS, e -> true);
        ChangeEvents.readUntil(BALANCES, e -> true);
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, CdcStack.KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(props)) {
            for (String topic : List.of(TRANSACTIONS, BALANCES)) {
                ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
                Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
                assertThat(config.get("cleanup.policy").value()).as(topic).isEqualTo("delete");
                assertThat(config.get("retention.ms").value()).as(topic).isEqualTo("-1");
                assertThat(config.get("retention.bytes").value()).as(topic).isEqualTo("-1");
            }
        }
    }

    @Test
    void registeredSchemasMatchTheContract() {
        sentinel();
        Event posting = ChangeEvents.readUntil(TRANSACTIONS, e -> true).getFirst();
        Event balance = ChangeEvents.readUntil(BALANCES, e -> true).getFirst();

        assertSameContract(posting.keySchema(), TransactionKey.getClassSchema());
        assertSameContract(posting.valueSchema(), TransactionPosted.getClassSchema());
        assertSameContract(balance.keySchema(), BalanceKey.getClassSchema());
        assertSameContract(balance.valueSchema(), BalanceChanged.getClassSchema());
    }

    // Connect adds connect.* properties and semantic-type names: compare names, types and field order only.
    private static void assertSameContract(Schema registered, Schema contract) {
        assertThat(SchemaNormalization.toParsingForm(registered))
                .as(contract.getFullName())
                .isEqualTo(SchemaNormalization.toParsingForm(contract));
    }

    private static List<Object> totals(Event e) {
        return List.of(e.key("account_id"), e.key("currency"), e.value("version"), e.value("available_debit"),
                e.value("available_credit"), e.value("held_debit"), e.value("held_credit"));
    }

    /** A posting on a fresh customer: once its events arrive, everything committed before it has too. */
    private String sentinel() {
        return id(record(credit(newCustomer(), 1, "EUR"), debit("int_BANK_NOSTRO_EUR", 1, "EUR")));
    }

    private JsonNode record(String... entries) {
        return record(body(entries));
    }

    private JsonNode record(String body) {
        HttpResponse<String> response = post(newKey(), body);
        if (response.statusCode() != 201) {
            throw new AssertionError("Expected 201, got " + response.statusCode() + ": " + response.body());
        }
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String idempotencyKey, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String body(String... entries) {
        return "{\"entries\": [" + String.join(", ", entries) + "]}";
    }

    private static String credit(String customer, long amount, String currency) {
        return customerEntry(customer, "CREDIT", amount, currency);
    }

    private static String debit(String account, long amount, String currency) {
        return account.startsWith("int_")
                ? internalEntry(account, "DEBIT", amount, currency)
                : customerEntry(account, "DEBIT", amount, currency);
    }

    private static String creditInternal(String account, long amount, String currency) {
        return internalEntry(account, "CREDIT", amount, currency);
    }

    private static String customerEntry(String account, String direction, long amount, String currency) {
        return """
                {"accountId": "%s", "bucket": "AVAILABLE", "direction": "%s", "amount": %d, "currency": "%s"}"""
                .formatted(account, direction, amount, currency);
    }

    private static String internalEntry(String account, String direction, long amount, String currency) {
        return """
                {"accountId": "%s", "direction": "%s", "amount": %d, "currency": "%s"}"""
                .formatted(account, direction, amount, currency);
    }

    private static String id(JsonNode recorded) {
        return recorded.get("id").asString();
    }

    private static Instant instant(Object text) {
        return Instant.parse((String) text);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String newCustomer() {
        StringBuilder id = new StringBuilder("cus_0");
        for (int i = 0; i < 25; i++) {
            id.append(TYPEID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(TYPEID_ALPHABET.length())));
        }
        return id.toString();
    }
}
