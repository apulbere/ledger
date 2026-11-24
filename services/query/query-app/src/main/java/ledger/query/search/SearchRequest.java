package ledger.query.search;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Validated search parameters. Each invalid input names exactly one parameter. */
record SearchRequest(String accountId, String currency, Instant occurredFrom, Instant occurredTo, int limit,
        Cursor after) {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    // Same grammar as the journal's account ids.
    private static final Pattern ACCOUNT_ID =
            Pattern.compile("cus_[0-7][0-9a-hjkmnp-tv-z]{25}|int_[A-Z][A-Z0-9_]{2,63}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    static SearchRequest of(String accountId, String currency, String occurredFrom, String occurredTo,
            String limit, String cursor) {
        if (!ACCOUNT_ID.matcher(accountId).matches()) {
            throw new InvalidParameterException("accountId", "accountId must be a cus_ or int_ account id");
        }
        if (currency != null && !CURRENCY.matcher(currency).matches()) {
            throw new InvalidParameterException("currency", "currency must be an ISO 4217 alphabetic code");
        }
        Instant from = timestamp("occurredFrom", occurredFrom);
        Instant to = timestamp("occurredTo", occurredTo);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidParameterException("occurredTo", "occurredTo must be after occurredFrom");
        }
        return new SearchRequest(accountId, currency, from, to, limit(limit),
                cursor == null ? null : Cursor.decode(cursor));
    }

    private static Instant timestamp(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidParameterException(name, name + " must be an RFC 3339 timestamp");
        }
    }

    private static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new InvalidParameterException("limit", "limit must be an integer from 1 to " + MAX_LIMIT);
    }
}
