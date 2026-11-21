package ledger.journal.posting;

import java.util.Optional;
import java.util.regex.Pattern;

public enum AccountType {
    CUSTOMER(Pattern.compile("^cus_[0-7][0-9a-hjkmnp-tv-z]{25}$")),
    INTERNAL(Pattern.compile("^int_[A-Z][A-Z0-9_]{2,63}$"));

    private final Pattern idPattern;

    AccountType(Pattern idPattern) {
        this.idPattern = idPattern;
    }

    /** The type is derived from the account id prefix; empty when the id matches neither grammar. */
    public static Optional<AccountType> of(String accountId) {
        for (AccountType type : values()) {
            if (type.idPattern.matcher(accountId).matches()) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
