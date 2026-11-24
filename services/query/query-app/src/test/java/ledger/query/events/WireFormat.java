package ledger.query.events;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

/** Encodes a contract record as the connector does: magic byte 0, schema id, Avro binary body. */
public final class WireFormat {

    private WireFormat() {
    }

    public static byte[] encode(int schemaId, SpecificRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0);
            out.write(ByteBuffer.allocate(4).putInt(schemaId).array());
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new SpecificDatumWriter<SpecificRecord>(record.getSchema()).write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
