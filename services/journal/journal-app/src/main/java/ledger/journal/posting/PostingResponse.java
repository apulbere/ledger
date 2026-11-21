package ledger.journal.posting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record PostingResponse(
        UUID id,
        String referenceId,
        Instant occurredAt,
        Instant postedAt,
        String description,
        ObjectNode metadata,
        List<Entry> entries,
        List<BalanceVersion> versions) {
}
