package ledger.journal.posting;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A validated entry, as stored in the posting log and returned to the client. */
public record Entry(
        int line,
        String accountId,
        AccountType accountType,
        @JsonInclude(JsonInclude.Include.NON_NULL) Bucket bucket,
        Direction direction,
        long amount,
        String currency) {
}
