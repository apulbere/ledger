package ledger.journal.posting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record RecordedPosting(UUID id, Instant occurredAt, Instant postedAt, List<BalanceVersion> versions) {
}
