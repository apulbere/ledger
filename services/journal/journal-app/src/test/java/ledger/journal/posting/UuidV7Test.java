package ledger.journal.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void ofEpochMillisProducesVersion7() {
        UUID id = UUID.ofEpochMillis(System.currentTimeMillis());

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }
}
