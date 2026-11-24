package ledger.query.events;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;
import ledger.journal.events.BalanceChanged;
import ledger.journal.events.BalanceKey;
import ledger.journal.events.TransactionKey;
import ledger.journal.events.TransactionPosted;
import org.apache.avro.Schema;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.util.ClassSecurityValidator;

/**
 * Decodes the Confluent wire format (magic byte 0, 4-byte big-endian schema id, Avro binary body) into the
 * {@code journal-events} contract records, resolving the writer schema the producer registered against the
 * contract's reader schema. Every failure throws; nothing is ever returned as null.
 */
public final class EventDecoder {

    private static final int HEADER = 5;

    static {
        // Avro only instantiates classes on its allow-list; trust exactly the contract records.
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(
                ClassSecurityValidator.getGlobal(),
                ClassSecurityValidator.builder()
                        .add(TransactionKey.class)
                        .add(TransactionPosted.class)
                        .add(BalanceKey.class)
                        .add(BalanceChanged.class)
                        .build()));
    }

    private final IntFunction<Schema> writerSchemas;

    public EventDecoder(IntFunction<Schema> writerSchemas) {
        this.writerSchemas = writerSchemas;
    }

    public <T extends SpecificRecord> T decode(byte[] bytes, Schema readerSchema) {
        if (bytes == null || bytes.length < HEADER) {
            throw new EventDecodingException("Not Confluent wire format: "
                    + (bytes == null ? "no bytes" : bytes.length + " bytes"));
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte magic = buffer.get();
        if (magic != 0) {
            throw new EventDecodingException("Not Confluent wire format: magic byte " + magic);
        }
        int schemaId = buffer.getInt();
        Schema writer = writerSchemas.apply(schemaId);
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, HEADER, bytes.length - HEADER, null);
            T record = new SpecificDatumReader<T>(writer, readerSchema).read(null, decoder);
            if (!decoder.isEnd()) {
                throw new EventDecodingException("Trailing bytes after " + readerSchema.getFullName());
            }
            return record;
        } catch (EventDecodingException e) {
            throw e;
        } catch (Exception e) {
            throw new EventDecodingException(
                    "Cannot decode " + readerSchema.getFullName() + " with writer schema " + schemaId, e);
        }
    }
}
