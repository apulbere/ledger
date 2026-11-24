package ledger.query.ingest;

import static ledger.query.Eventually.eventually;
import static ledger.query.Eventually.throughout;
import static ledger.query.Fixtures.credit;
import static ledger.query.Fixtures.customer;
import static ledger.query.Fixtures.debit;
import static ledger.query.Fixtures.held;
import static ledger.query.Fixtures.internal;
import static ledger.query.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ledger.journal.events.TransactionPosted;
import ledger.query.TestcontainersConfiguration;
import ledger.query.events.JournalEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Ingestion scenarios of query/transaction-search, through Kafka and Schema Registry. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostingIngestionTest {

    private static final Instant T = Instant.parse("2026-11-21T10:00:00Z");

    @Autowired
    JournalEvents journal;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Value("${spring.kafka.consumer.group-id}")
    String groupId;

    @Test
    void postingIndexedByEveryAccount() {
        String customer = customer(), settlement = internal();
        TransactionPosted t1 = posting(T, debit(customer, 5000, "EUR"), credit(settlement, 5000, "EUR"));
        journal.posting(t1);

        eventually(() -> assertThat(indexRows(t1)).containsExactlyInAnyOrder(
                customer + " {EUR}", settlement + " {EUR}"));
        assertThat(postingCount(t1)).isEqualTo(1);
    }

    @Test
    void severalEntriesOnOneAccountGiveOneIndexRow() {
        String customer = customer();
        TransactionPosted hold = posting(T, debit(customer, 5730, "EUR"), held(credit(customer, 5730, "EUR")));
        journal.posting(hold);

        eventually(() -> assertThat(indexRows(hold)).containsExactly(customer + " {EUR}"));
    }

    @Test
    void fxPostingIndexesBothCurrencies() {
        String customer = customer(), fxEur = internal(), fxUsd = internal();
        TransactionPosted fx = posting(T,
                debit(customer, 10000, "EUR"), credit(fxEur, 10000, "EUR"),
                debit(fxUsd, 11000, "USD"), credit(customer, 11000, "USD"));
        journal.posting(fx);

        eventually(() -> assertThat(indexRows(fx)).containsExactlyInAnyOrder(
                customer + " {EUR,USD}", fxEur + " {EUR}", fxUsd + " {USD}"));
    }

    @Test
    void redeliveredPostingChangesNothing() {
        String customer = customer(), settlement = internal();
        TransactionPosted t1 = posting(T, debit(customer, 5000, "EUR"), credit(settlement, 5000, "EUR"));
        journal.posting(t1);
        // Same id again, with a different description and accounts to prove nothing is overwritten or added.
        journal.posting(TransactionPosted.newBuilder(
                posting(t1.getId(), T, debit(customer(), 1, "USD"), credit(settlement, 1, "USD")))
                .setDescription("changed").build());
        TransactionPosted sentinel = posting(T, debit(customer(), 1, "EUR"), credit(settlement, 1, "EUR"));
        journal.posting(sentinel);

        eventually(() -> assertThat(postingCount(sentinel)).isEqualTo(1));
        assertThat(postingCount(t1)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT description FROM query.transaction WHERE id = ?")
                .param(UUID.fromString(t1.getId())).query(String.class).optional()).isEmpty();
        assertThat(indexRows(t1)).containsExactlyInAnyOrder(customer + " {EUR}", settlement + " {EUR}");
    }

    @Test
    void undecodableEventBlocksLaterPostings() {
        String settlement = internal();
        TransactionPosted before = posting(T, debit(customer(), 1, "EUR"), credit(settlement, 1, "EUR"));
        journal.posting(before);
        eventually(() -> assertThat(postingCount(before)).isEqualTo(1));

        long poison = journal.raw(JournalEvents.TRANSACTION_TOPIC, new byte[] {1}, new byte[] {0, 0, 0, 0, 1, 7});
        TransactionPosted t2 = posting(T, debit(customer(), 2, "EUR"), credit(settlement, 2, "EUR"));
        journal.posting(t2);
        try {
            throughout(Duration.ofSeconds(5), () -> {
                assertThat(postingCount(t2)).isZero();
                assertThat(postingCount(before)).isEqualTo(1);
            });
        } finally {
            PoisonRecords.skip(listeners, journal.bootstrapServers(), groupId, JournalEvents.TRANSACTION_TOPIC, poison);
        }
        eventually(() -> assertThat(postingCount(t2)).isEqualTo(1));
    }

    private List<String> indexRows(TransactionPosted posting) {
        return jdbc.sql("""
                SELECT account_id || ' ' || currencies::text FROM query.account_transaction WHERE transaction_id = ?
                """).param(UUID.fromString(posting.getId())).query(String.class).list();
    }

    private long postingCount(TransactionPosted posting) {
        return jdbc.sql("SELECT count(*) FROM query.transaction WHERE id = ?")
                .param(UUID.fromString(posting.getId())).query(Long.class).single();
    }
}
