package ledger.query.ingest;

import static ledger.query.Eventually.eventually;
import static ledger.query.Fixtures.balance;
import static ledger.query.Fixtures.credit;
import static ledger.query.Fixtures.customer;
import static ledger.query.Fixtures.debit;
import static ledger.query.Fixtures.internal;
import static ledger.query.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import ledger.journal.events.TransactionPosted;
import ledger.query.TestcontainersConfiguration;
import ledger.query.events.JournalEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Events published while query had never consumed: its own containers (empty database, new group), consumers held
 * back until the events are on the topics.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Import(TestcontainersConfiguration.class)
class BackfillTest {

    @Autowired
    JournalEvents journal;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Test
    void eventsPublishedBeforeStartupAreIngested() {
        String customer = customer(), settlement = internal();
        TransactionPosted t1 = posting(Instant.parse("2026-11-20T10:00:00Z"),
                credit(customer, 5000, "EUR"), debit(settlement, 5000, "EUR"));
        TransactionPosted t2 = posting(Instant.parse("2026-11-21T10:00:00Z"),
                debit(customer, 1000, "EUR"), credit(settlement, 1000, "EUR"));
        journal.posting(t1);
        journal.balance(balance(customer, "EUR", 1, t1.getId(), 5000));
        journal.posting(t2);
        journal.balance(balance(customer, "EUR", 2, t2.getId(), 5000));
        assertThat(jdbc.sql("SELECT count(*) FROM query.transaction").query(Long.class).single()).isZero();

        listeners.start();

        eventually(() -> {
            assertThat(jdbc.sql("SELECT count(*) FROM query.account_transaction WHERE account_id = ?")
                    .param(customer).query(Long.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT count(*) FROM query.account_transaction WHERE account_id = ?")
                    .param(settlement).query(Long.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("""
                    SELECT version FROM query.balance_version WHERE account_id = ? ORDER BY version
                    """).param(customer).query(Long.class).list()).containsExactly(1L, 2L);
        });
        assertThat(jdbc.sql("SELECT count(*) FROM query.transaction WHERE id IN (?, ?)")
                .params(UUID.fromString(t1.getId()), UUID.fromString(t2.getId())).query(Long.class).single())
                .isEqualTo(2);
        assertThat(JournalEvents.TRANSACTION_TOPIC).isNotBlank();
    }
}
