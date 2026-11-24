package ledger.query.ingest;

import static ledger.query.Eventually.eventually;
import static ledger.query.Eventually.throughout;
import static ledger.query.Fixtures.balance;
import static ledger.query.Fixtures.customer;
import static ledger.query.Fixtures.postingId;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import ledger.query.TestcontainersConfiguration;
import ledger.query.events.JournalEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Scenarios of query/account-balances, through Kafka and Schema Registry. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BalanceIngestionTest {

    record Version(long version, long availableCredit, UUID lastTransactionId) {
    }

    @Autowired
    JournalEvents journal;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Value("${spring.kafka.consumer.group-id}")
    String groupId;

    @Test
    void allVersionsHeldHighestIsCurrent() {
        String account = customer();
        String t1 = postingId(), t2 = postingId(), t3 = postingId();
        journal.balance(balance(account, "EUR", 1, t1, 100));
        journal.balance(balance(account, "EUR", 2, t2, 300));
        journal.balance(balance(account, "EUR", 3, t3, 600));

        eventually(() -> assertThat(versions(account, "EUR")).containsExactly(
                new Version(1, 100, UUID.fromString(t1)),
                new Version(2, 300, UUID.fromString(t2)),
                new Version(3, 600, UUID.fromString(t3))));
        assertThat(current(account, "EUR")).isEqualTo(new Version(3, 600, UUID.fromString(t3)));
    }

    @Test
    void versionsLinkedToTheirPosting() {
        String account = customer();
        String t1 = postingId();
        for (long v = 1; v < 18; v++) {
            journal.balance(balance(account, "EUR", v, postingId(), v));
        }
        for (long v = 1; v < 4; v++) {
            journal.balance(balance(account, "USD", v, postingId(), v));
        }
        journal.balance(balance(account, "EUR", 18, t1, 18));
        journal.balance(balance(account, "USD", 4, t1, 4));

        eventually(() -> assertThat(jdbc.sql("""
                SELECT account_id || ' ' || currency || ' ' || version
                FROM query.balance_version WHERE last_transaction_id = ? ORDER BY currency
                """).param(UUID.fromString(t1)).query(String.class).list())
                .containsExactly(account + " EUR 18", account + " USD 4"));
    }

    @Test
    void redeliveredVersionChangesNothing() {
        String account = customer();
        String t3 = postingId();
        journal.balance(balance(account, "EUR", 1, postingId(), 100));
        journal.balance(balance(account, "EUR", 2, postingId(), 200));
        journal.balance(balance(account, "EUR", 3, t3, 300));
        // Same version again, with different totals to prove the held row is not overwritten.
        journal.balance(balance(account, "EUR", 3, postingId(), 999));
        String sentinel = customer();
        journal.balance(balance(sentinel, "EUR", 1, postingId(), 1));

        eventually(() -> assertThat(versions(sentinel, "EUR")).hasSize(1));
        assertThat(versions(account, "EUR")).filteredOn(v -> v.version() == 3)
                .containsExactly(new Version(3, 300, UUID.fromString(t3)));
    }

    @Test
    void lateOlderVersionHeldWithoutChangingCurrent() {
        String account = customer();
        String t3 = postingId();
        journal.balance(balance(account, "EUR", 1, postingId(), 100));
        journal.balance(balance(account, "EUR", 3, t3, 300));
        eventually(() -> assertThat(versions(account, "EUR")).hasSize(2));

        journal.balance(balance(account, "EUR", 2, postingId(), 200));

        eventually(() -> assertThat(versions(account, "EUR")).extracting(Version::version).containsExactly(1L, 2L, 3L));
        assertThat(current(account, "EUR")).isEqualTo(new Version(3, 300, UUID.fromString(t3)));
    }

    @Test
    void undecodableEventBlocksLaterVersions() {
        String account = customer();
        long poison = journal.raw(JournalEvents.BALANCE_TOPIC, new byte[] {1}, new byte[] {9, 9, 9});
        journal.balance(balance(account, "EUR", 5, postingId(), 500));
        try {
            throughout(Duration.ofSeconds(5), () -> assertThat(versions(account, "EUR")).isEmpty());
        } finally {
            PoisonRecords.skip(listeners, journal.bootstrapServers(), groupId, JournalEvents.BALANCE_TOPIC, poison);
        }
        eventually(() -> assertThat(versions(account, "EUR")).extracting(Version::version).containsExactly(5L));
    }

    private List<Version> versions(String account, String currency) {
        return jdbc.sql("""
                SELECT version, available_credit, last_transaction_id FROM query.balance_version
                WHERE account_id = ? AND currency = ? ORDER BY version
                """).params(account, currency)
                .query((rs, i) -> new Version(rs.getLong(1), rs.getLong(2), rs.getObject(3, UUID.class)))
                .list();
    }

    private Version current(String account, String currency) {
        return jdbc.sql("""
                SELECT version, available_credit, last_transaction_id FROM query.balance_version
                WHERE account_id = ? AND currency = ? ORDER BY version DESC LIMIT 1
                """).params(account, currency)
                .query((rs, i) -> new Version(rs.getLong(1), rs.getLong(2), rs.getObject(3, UUID.class)))
                .single();
    }
}
