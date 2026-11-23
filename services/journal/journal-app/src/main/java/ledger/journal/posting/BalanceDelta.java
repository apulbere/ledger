package ledger.journal.posting;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What a posting adds to the debit and credit totals of one customer balance. Debits and credits are never netted. */
record BalanceDelta(String accountId, String currency,
        long availableDebit, long availableCredit, long heldDebit, long heldCredit) {

    private record Key(String accountId, String currency) {
    }

    // Balances are locked in this order, so two postings can never deadlock.
    private static final Comparator<Key> LOCK_ORDER =
            Comparator.comparing(Key::accountId).thenComparing(Key::currency);

    private static final int AVAILABLE_DEBIT = 0;
    private static final int AVAILABLE_CREDIT = 1;
    private static final int HELD_DEBIT = 2;
    private static final int HELD_CREDIT = 3;

    /** One delta per customer (account, currency) touched by the entries, in lock order. Internal entries are skipped. */
    static List<BalanceDelta> of(List<Entry> entries) {
        Map<Key, long[]> totals = new TreeMap<>(LOCK_ORDER);
        for (Entry entry : entries) {
            if (entry.accountType() != AccountType.CUSTOMER) {
                continue;
            }
            long[] delta = totals.computeIfAbsent(new Key(entry.accountId(), entry.currency()), k -> new long[4]);
            delta[slot(entry.bucket(), entry.direction())] += entry.amount();
        }
        return totals.entrySet().stream()
                .map(e -> {
                    long[] d = e.getValue();
                    return new BalanceDelta(e.getKey().accountId(), e.getKey().currency(),
                            d[AVAILABLE_DEBIT], d[AVAILABLE_CREDIT], d[HELD_DEBIT], d[HELD_CREDIT]);
                })
                .toList();
    }

    private static int slot(Bucket bucket, Direction direction) {
        boolean debit = direction == Direction.DEBIT;
        return bucket == Bucket.AVAILABLE
                ? (debit ? AVAILABLE_DEBIT : AVAILABLE_CREDIT)
                : (debit ? HELD_DEBIT : HELD_CREDIT);
    }
}
