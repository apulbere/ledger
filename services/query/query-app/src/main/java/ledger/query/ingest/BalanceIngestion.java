package ledger.query.ingest;

import java.util.UUID;
import ledger.journal.events.BalanceChanged;
import ledger.query.events.EventDecoder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Keeps every balance version; a version already held (redelivery) changes nothing. */
@Component
class BalanceIngestion {

    static final String LISTENER_ID = "balances";

    private final EventDecoder decoder;
    private final JdbcClient jdbc;

    BalanceIngestion(EventDecoder decoder, JdbcClient jdbc) {
        this.decoder = decoder;
        this.jdbc = jdbc;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "ledger.journal.balance")
    void onBalanceChanged(ConsumerRecord<byte[], byte[]> record) {
        BalanceChanged balance = decoder.decode(record.value(), BalanceChanged.getClassSchema());
        jdbc.sql("""
                INSERT INTO query.balance_version (account_id, currency, version,
                    available_debit, available_credit, held_debit, held_credit, last_transaction_id)
                VALUES (:accountId, :currency, :version,
                    :availableDebit, :availableCredit, :heldDebit, :heldCredit, :lastTransactionId)
                ON CONFLICT (account_id, currency, version) DO NOTHING
                """)
                .param("accountId", balance.getAccountId())
                .param("currency", balance.getCurrency())
                .param("version", balance.getVersion())
                .param("availableDebit", balance.getAvailableDebit())
                .param("availableCredit", balance.getAvailableCredit())
                .param("heldDebit", balance.getHeldDebit())
                .param("heldCredit", balance.getHeldCredit())
                .param("lastTransactionId", UUID.fromString(balance.getLastTransactionId()))
                .update();
    }
}
