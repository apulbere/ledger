package ledger.query.events;

import static org.assertj.core.api.Assertions.assertThat;

import ledger.journal.events.BalanceChanged;
import ledger.journal.events.TransactionPosted;
import ledger.query.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** What the test producer registers and encodes, the application decodes through the real Schema Registry. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaRegistryRoundTripTest {

    @Autowired
    JournalEvents journal;

    @Autowired
    EventDecoder decoder;

    @Test
    void postingRoundTrip() {
        TransactionPosted posting = TransactionPosted.newBuilder()
                .setId("019ab7c4-5e2f-7a31-9c0d-4b8e2f6a1d37")
                .setIdempotencyKey("k-1")
                .setReferenceId(null)
                .setOccurredAt("2026-11-21T12:30:00Z")
                .setPostedAt("2026-11-21T12:30:01.204Z")
                .setDescription(null)
                .setMetadata(null)
                .setEntries("[]")
                .setVersions("[]")
                .build();

        byte[] bytes = journal.encode(JournalEvents.TRANSACTION_TOPIC + "-value", posting);

        assertThat(decoder.<TransactionPosted>decode(bytes, TransactionPosted.getClassSchema())).isEqualTo(posting);
    }

    @Test
    void balanceRoundTrip() {
        BalanceChanged balance = BalanceChanged.newBuilder()
                .setAccountId("cus_A").setCurrency("EUR").setVersion(1)
                .setLastTransactionId("019ab7c4-5e2f-7a31-9c0d-4b8e2f6a1d37")
                .setAvailableDebit(0).setAvailableCredit(5000).setHeldDebit(0).setHeldCredit(0)
                .build();

        byte[] bytes = journal.encode(JournalEvents.BALANCE_TOPIC + "-value", balance);

        assertThat(decoder.<BalanceChanged>decode(bytes, BalanceChanged.getClassSchema())).isEqualTo(balance);
    }
}
