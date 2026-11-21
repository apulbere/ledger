package ledger.journal.posting;

import static ledger.journal.posting.Fixtures.credit;
import static ledger.journal.posting.Fixtures.debit;
import static ledger.journal.posting.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** The balance table must always be derivable from the posting log, across everything recorded so far. */
class BalanceReconstructionTest extends PostingApiSupport {

    @Test
    void balancesEqualTheSumOfRecordedEntries() {
        recordMixedSequence();

        Map<String, String> fromLog = jdbc.sql("""
                SELECT e->>'accountId' AS account_id, e->>'currency' AS currency,
                       sum(CASE WHEN e->>'bucket' = 'AVAILABLE' THEN signed ELSE 0 END) AS available,
                       sum(CASE WHEN e->>'bucket' = 'HELD' THEN signed ELSE 0 END) AS held
                FROM journal.transaction t,
                     jsonb_array_elements(t.entries) e,
                     LATERAL (SELECT CASE e->>'direction' WHEN 'CREDIT' THEN 1 ELSE -1 END
                                     * (e->>'amount')::bigint AS signed) s
                WHERE e->>'accountType' = 'CUSTOMER'
                GROUP BY 1, 2
                """).query((rs, n) -> Map.entry(rs.getString(1) + "/" + rs.getString(2),
                        rs.getLong(3) + "/" + rs.getLong(4)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, String> maintained = jdbc.sql("SELECT account_id, currency, available, held FROM journal.balance")
                .query((rs, n) -> Map.entry(rs.getString(1) + "/" + rs.getString(2),
                        rs.getLong(3) + "/" + rs.getLong(4)))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(maintained).isNotEmpty().isEqualTo(fromLog);
    }

    @Test
    void recordedVersionsAreContiguousUpToTheBalanceVersion() {
        recordMixedSequence();

        List<Map.Entry<String, String>> balances = jdbc.sql("SELECT account_id, currency FROM journal.balance")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list();

        assertThat(balances).isNotEmpty().allSatisfy(b -> {
            long version = balance(b.getKey(), b.getValue()).orElseThrow().version();
            assertThat(recordedVersions(b.getKey(), b.getValue()))
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, version).boxed().toList());
        });
    }

    @Test
    void lastTransactionListsTheBalanceAtItsCurrentVersion() {
        recordMixedSequence();

        long mismatches = jdbc.sql("""
                SELECT count(*)
                FROM journal.balance b
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM journal.transaction t, jsonb_array_elements(t.versions) v
                    WHERE t.id = b.last_transaction_id
                      AND v->>'accountId' = b.account_id
                      AND v->>'currency' = b.currency
                      AND (v->>'version')::bigint = b.version)
                """).query(Long.class).single();

        assertThat(mismatches).isZero();
    }

    private void recordMixedSequence() {
        String a = newCustomer();
        String b = newCustomer();
        record(posting(credit(a, "AVAILABLE", 50000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 50000, "EUR")));
        record(posting(credit(b, "AVAILABLE", 3000, "USD"), debit("int_BANK_NOSTRO_USD", null, 3000, "USD")));
        record(posting(debit(a, "AVAILABLE", 5000, "EUR"), credit(a, "HELD", 5000, "EUR")));
        record(posting(debit(a, "HELD", 5000, "EUR"), credit("int_SETTLEMENT_VISA", null, 5000, "EUR")));
        record(posting(
                debit(a, "AVAILABLE", 10000, "EUR"),
                credit("int_FX_POSITION_EUR", null, 10000, "EUR"),
                debit("int_FX_POSITION_USD", null, 11000, "USD"),
                credit(a, "AVAILABLE", 11000, "USD")));
        record(posting(debit(a, "AVAILABLE", 2000, "USD"), credit(b, "AVAILABLE", 1990, "USD"),
                credit("int_FEES_USD", null, 10, "USD")));
        record(posting(debit(b, "AVAILABLE", 9000, "USD"), credit("int_SETTLEMENT_VISA", null, 9000, "USD")));
        record(posting(debit("int_SETTLEMENT_VISA", null, 700, "JPY"), credit("int_BANK_NOSTRO_JPY", null, 700, "JPY")));
    }
}
