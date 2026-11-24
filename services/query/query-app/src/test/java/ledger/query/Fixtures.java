package ledger.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import ledger.journal.events.BalanceChanged;
import ledger.journal.events.TransactionPosted;
import tools.jackson.databind.json.JsonMapper;

/** Journal events as the connector publishes them, for accounts unique to each test. */
public final class Fixtures {

    private static final String TYPEID_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong CLOCK = new AtomicLong(System.currentTimeMillis());

    private Fixtures() {
    }

    public record Entry(String accountId, String bucket, String direction, long amount, String currency) {
    }

    /** A fresh customer account id following the journal's grammar. */
    public static String customer() {
        StringBuilder id = new StringBuilder("cus_0");
        for (int i = 0; i < 25; i++) {
            id.append(TYPEID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(TYPEID_ALPHABET.length())));
        }
        return id.toString();
    }

    /** A fresh internal account id. */
    public static String internal() {
        return "int_TEST_" + Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36).toUpperCase();
    }

    /** A UUIDv7 posting id, strictly increasing within the test JVM. */
    public static String postingId() {
        return UUID.ofEpochMillis(CLOCK.incrementAndGet()).toString();
    }

    public static Entry debit(String accountId, long amount, String currency) {
        return new Entry(accountId, accountId.startsWith("cus_") ? "AVAILABLE" : null, "DEBIT", amount, currency);
    }

    public static Entry credit(String accountId, long amount, String currency) {
        return new Entry(accountId, accountId.startsWith("cus_") ? "AVAILABLE" : null, "CREDIT", amount, currency);
    }

    public static Entry held(Entry entry) {
        return new Entry(entry.accountId(), "HELD", entry.direction(), entry.amount(), entry.currency());
    }

    public static TransactionPosted posting(Instant occurredAt, Entry... entries) {
        return posting(postingId(), occurredAt, entries);
    }

    public static TransactionPosted posting(String id, Instant occurredAt, Entry... entries) {
        return TransactionPosted.newBuilder()
                .setId(id)
                .setIdempotencyKey("key-" + id)
                .setReferenceId(null)
                .setOccurredAt(occurredAt.toString())
                .setPostedAt(Instant.now().toString())
                .setDescription(null)
                .setMetadata(null)
                .setEntries(entriesJson(entries))
                .setVersions(versionsJson(entries))
                .build();
    }

    public static BalanceChanged balance(String accountId, String currency, long version, String postingId,
            long availableCredit) {
        return BalanceChanged.newBuilder()
                .setAccountId(accountId).setCurrency(currency).setVersion(version)
                .setLastTransactionId(postingId)
                .setAvailableDebit(0).setAvailableCredit(availableCredit).setHeldDebit(0).setHeldCredit(0)
                .build();
    }

    private static String entriesJson(Entry... entries) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            Entry e = entries[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", i + 1);
            m.put("accountId", e.accountId());
            m.put("accountType", e.accountId().startsWith("cus_") ? "CUSTOMER" : "INTERNAL");
            if (e.bucket() != null) {
                m.put("bucket", e.bucket());
            }
            m.put("direction", e.direction());
            m.put("amount", e.amount());
            m.put("currency", e.currency());
            list.add(m);
        }
        return JSON.writeValueAsString(list);
    }

    private static String versionsJson(Entry... entries) {
        Set<List<String>> balances = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.accountId().startsWith("cus_")) {
                balances.add(List.of(e.accountId(), e.currency()));
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (List<String> b : balances) {
            list.add(Map.of("accountId", b.get(0), "currency", b.get(1), "version", 1));
        }
        return JSON.writeValueAsString(list);
    }
}
