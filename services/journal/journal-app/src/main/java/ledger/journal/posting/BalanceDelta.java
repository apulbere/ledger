package ledger.journal.posting;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The net change a posting applies to one customer balance. */
record BalanceDelta(String accountId, String currency, long available, long held) {

    private record Key(String accountId, String currency) {
    }

    // Balances are locked in this order, so two postings can never deadlock.
    private static final Comparator<Key> LOCK_ORDER =
            Comparator.comparing(Key::accountId).thenComparing(Key::currency);

    /** One delta per customer (account, currency) touched by the entries, in lock order. Internal entries are skipped. */
    static List<BalanceDelta> of(List<Entry> entries) {
        Map<Key, long[]> net = new TreeMap<>(LOCK_ORDER);
        for (Entry entry : entries) {
            if (entry.accountType() != AccountType.CUSTOMER) {
                continue;
            }
            long signed = entry.direction() == Direction.CREDIT ? entry.amount() : -entry.amount();
            long[] delta = net.computeIfAbsent(new Key(entry.accountId(), entry.currency()), k -> new long[2]);
            delta[entry.bucket() == Bucket.AVAILABLE ? 0 : 1] += signed;
        }
        return net.entrySet().stream()
                .map(e -> new BalanceDelta(e.getKey().accountId(), e.getKey().currency(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }
}
