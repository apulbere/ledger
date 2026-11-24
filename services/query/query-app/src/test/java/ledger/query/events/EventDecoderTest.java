package ledger.query.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import ledger.journal.events.BalanceChanged;
import ledger.journal.events.BalanceKey;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

class EventDecoderTest {

    private static final Map<Integer, Schema> REGISTRY = Map.of(
            1, BalanceKey.getClassSchema(),
            2, BalanceChanged.getClassSchema());

    private final EventDecoder decoder = new EventDecoder(id -> {
        Schema schema = REGISTRY.get(id);
        if (schema == null) {
            throw new EventDecodingException("Schema " + id + " not available");
        }
        return schema;
    });

    private static BalanceChanged balance() {
        return BalanceChanged.newBuilder()
                .setAccountId("cus_A").setCurrency("EUR").setVersion(3)
                .setLastTransactionId("019ab7c4-5e2f-7a31-9c0d-4b8e2f6a1d37")
                .setAvailableDebit(200).setAvailableCredit(1000).setHeldDebit(0).setHeldCredit(50)
                .build();
    }

    @Test
    void decodesValidRecord() {
        BalanceChanged decoded = decoder.decode(WireFormat.encode(2, balance()), BalanceChanged.getClassSchema());

        assertThat(decoded).isEqualTo(balance());
        assertThat(decoded.getAccountId()).isInstanceOf(String.class);
    }

    @Test
    void rejectsWrongMagicByte() {
        byte[] bytes = WireFormat.encode(2, balance());
        bytes[0] = 1;

        assertThatThrownBy(() -> decoder.decode(bytes, BalanceChanged.getClassSchema()))
                .isInstanceOf(EventDecodingException.class)
                .hasMessageContaining("magic byte 1");
    }

    @Test
    void rejectsUnknownSchemaId() {
        assertThatThrownBy(() -> decoder.decode(WireFormat.encode(99, balance()), BalanceChanged.getClassSchema()))
                .isInstanceOf(EventDecodingException.class)
                .hasMessageContaining("99");
    }

    @Test
    void rejectsTruncatedBody() {
        byte[] bytes = WireFormat.encode(2, balance());

        assertThatThrownBy(() -> decoder.decode(Arrays.copyOf(bytes, bytes.length - 3), BalanceChanged.getClassSchema()))
                .isInstanceOf(EventDecodingException.class);
    }

    @Test
    void rejectsMissingHeader() {
        assertThatThrownBy(() -> decoder.decode(new byte[] {0, 0}, BalanceChanged.getClassSchema()))
                .isInstanceOf(EventDecodingException.class);
        assertThatThrownBy(() -> decoder.decode(null, BalanceChanged.getClassSchema()))
                .isInstanceOf(EventDecodingException.class);
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] bytes = WireFormat.encode(2, balance());
        byte[] longer = Arrays.copyOf(bytes, bytes.length + 2);

        assertThatThrownBy(() -> decoder.decode(longer, BalanceChanged.getClassSchema()))
                .isInstanceOf(EventDecodingException.class);
    }
}
