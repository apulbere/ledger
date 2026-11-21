package ledger.journal.posting;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import ledger.journal.posting.PostingRequest.EntryRequest;

/** Checks a parsed request against the posting rules and turns it into numbered, typed entries. */
final class PostingValidator {

    static final int MIN_ENTRIES = 2;
    static final int MAX_ENTRIES = 100;
    static final long MAX_AMOUNT = 1_000_000_000_000_000L;
    static final int MAX_REFERENCE_ID_LENGTH = 128;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private PostingValidator() {
    }

    static ValidatedPosting validate(PostingRequest request) {
        checkShape(request);
        int count = request.entries().size();
        if (count < MIN_ENTRIES) {
            throw PostingProblem.invalid("TOO_FEW_ENTRIES",
                    "A posting needs at least " + MIN_ENTRIES + " entries, got " + count);
        }
        if (count > MAX_ENTRIES) {
            throw PostingProblem.invalid("TOO_MANY_ENTRIES",
                    "A posting allows at most " + MAX_ENTRIES + " entries, got " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(entry(i + 1, request.entries().get(i)));
        }
        checkBalanced(entries);
        return new ValidatedPosting(request.referenceId(), request.occurredAt(), request.description(),
                request.metadata(), List.copyOf(entries));
    }

    // Missing required fields and over-long strings are malformed requests, not domain errors.
    private static void checkShape(PostingRequest request) {
        if (request.entries() == null) {
            throw PostingProblem.malformed("entries is required");
        }
        if (request.referenceId() != null && request.referenceId().length() > MAX_REFERENCE_ID_LENGTH) {
            throw PostingProblem.malformed("referenceId is longer than " + MAX_REFERENCE_ID_LENGTH + " characters");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw PostingProblem.malformed("description is longer than " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        for (int i = 0; i < request.entries().size(); i++) {
            EntryRequest entry = request.entries().get(i);
            int line = i + 1;
            if (entry == null) {
                throw PostingProblem.malformed("entry " + line + " is null");
            }
            requireField(entry.accountId(), "accountId", line);
            requireField(entry.direction(), "direction", line);
            requireField(entry.amount(), "amount", line);
            requireField(entry.currency(), "currency", line);
        }
    }

    private static void requireField(Object value, String field, int line) {
        if (value == null) {
            throw PostingProblem.malformed("entry " + line + ": " + field + " is required");
        }
    }

    private static Entry entry(int line, EntryRequest request) {
        AccountType type = AccountType.of(request.accountId())
                .orElseThrow(() -> PostingProblem.invalid("INVALID_ACCOUNT_ID",
                        "Account id '" + request.accountId() + "' is neither a cus_ nor an int_ id", line));
        Bucket bucket = bucket(type, request.bucket(), line);
        long amount = request.amount();
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw PostingProblem.invalid("INVALID_AMOUNT",
                    "Amount must be between 1 and " + MAX_AMOUNT + " minor units, got " + amount, line);
        }
        if (currency(request.currency()).isEmpty()) {
            throw PostingProblem.invalid("UNKNOWN_CURRENCY",
                    "'" + request.currency() + "' is not an ISO 4217 currency with minor units", line);
        }
        return new Entry(line, request.accountId(), type, bucket, request.direction(), amount, request.currency());
    }

    private static Bucket bucket(AccountType type, String value, int line) {
        if (type == AccountType.INTERNAL) {
            if (value != null) {
                throw PostingProblem.invalid("INVALID_BUCKET", "Internal account entries do not take a bucket", line);
            }
            return null;
        }
        if (value == null) {
            throw PostingProblem.invalid("INVALID_BUCKET", "Customer account entries need a bucket", line);
        }
        try {
            return Bucket.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw PostingProblem.invalid("INVALID_BUCKET", "Bucket must be AVAILABLE or HELD, got '" + value + "'", line);
        }
    }

    private static Optional<Currency> currency(String code) {
        try {
            Currency currency = Currency.getInstance(code);
            // Funds and metals such as XAU or XXX have no minor units (-1).
            return currency.getDefaultFractionDigits() >= 0 ? Optional.of(currency) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void checkBalanced(List<Entry> entries) {
        Map<String, long[]> sums = new TreeMap<>();
        for (Entry entry : entries) {
            long[] sum = sums.computeIfAbsent(entry.currency(), c -> new long[2]);
            sum[entry.direction() == Direction.DEBIT ? 0 : 1] += entry.amount();
        }
        sums.forEach((currency, sum) -> {
            if (sum[0] != sum[1]) {
                throw PostingProblem.invalid("UNBALANCED",
                        currency + " debits " + sum[0] + " do not equal credits " + sum[1], null,
                        Map.of("currency", currency, "debits", sum[0], "credits", sum[1]));
            }
        });
    }
}
