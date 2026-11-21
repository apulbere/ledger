package ledger.journal.posting;

import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

record ValidatedPosting(
        String referenceId,
        OffsetDateTime occurredAt,
        String description,
        ObjectNode metadata,
        List<Entry> entries) {
}
