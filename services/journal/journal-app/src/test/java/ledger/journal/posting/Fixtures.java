package ledger.journal.posting;

import java.util.List;
import ledger.journal.posting.PostingRequest.EntryRequest;

final class Fixtures {

    static final String CUS_A = "cus_01jcz8k2m4f7r9t3vbx5w6y8za";
    static final String CUS_B = "cus_01jcz8k2m4f7r9t3vbx5w6y8zb";
    static final String CUS_C = "cus_01jcz8k2m4f7r9t3vbx5w6y8zc";

    private Fixtures() {
    }

    static EntryRequest debit(String accountId, String bucket, long amount, String currency) {
        return new EntryRequest(accountId, bucket, Direction.DEBIT, amount, currency);
    }

    static EntryRequest credit(String accountId, String bucket, long amount, String currency) {
        return new EntryRequest(accountId, bucket, Direction.CREDIT, amount, currency);
    }

    static PostingRequest posting(EntryRequest... entries) {
        return new PostingRequest(null, null, null, null, List.of(entries));
    }
}
