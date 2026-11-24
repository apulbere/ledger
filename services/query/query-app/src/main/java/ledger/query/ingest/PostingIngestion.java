package ledger.query.ingest;

import ledger.journal.events.TransactionPosted;
import ledger.query.events.EventDecoder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PostingIngestion {

    static final String LISTENER_ID = "postings";

    private final EventDecoder decoder;
    private final PostingStore store;

    PostingIngestion(EventDecoder decoder, PostingStore store) {
        this.decoder = decoder;
        this.store = store;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "ledger.journal.transaction")
    void onTransactionPosted(ConsumerRecord<byte[], byte[]> record) {
        store.store(decoder.decode(record.value(), TransactionPosted.getClassSchema()));
    }
}
