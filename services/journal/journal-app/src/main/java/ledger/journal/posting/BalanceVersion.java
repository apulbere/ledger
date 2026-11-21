package ledger.journal.posting;

/** The version of a customer balance after a posting changed it. */
public record BalanceVersion(String accountId, String currency, long version) {
}
