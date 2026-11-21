package ledger.journal.posting;

import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public record PostingRequest(
        String referenceId,
        OffsetDateTime occurredAt,
        String description,
        ObjectNode metadata,
        List<EntryRequest> entries) {

    // bucket is a string so an unknown value is a domain error (INVALID_BUCKET), not a parse error.
    public record EntryRequest(String accountId, String bucket, Direction direction, Long amount, String currency) {
    }
}
