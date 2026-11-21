package ledger.journal.posting;

import static ledger.journal.posting.Fixtures.CUS_A;
import static ledger.journal.posting.Fixtures.CUS_B;
import static ledger.journal.posting.Fixtures.credit;
import static ledger.journal.posting.Fixtures.debit;
import static ledger.journal.posting.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;

import ledger.journal.posting.PostingRequest.EntryRequest;
import org.junit.jupiter.api.Test;

class BalanceDeltaTest {

    @Test
    void holdMovesBetweenBuckets() {
        assertThat(deltas(
                debit(CUS_A, "AVAILABLE", 5000, "EUR"),
                credit(CUS_A, "HELD", 5000, "EUR")))
                .containsExactly(new BalanceDelta(CUS_A, "EUR", -5000, 5000));
    }

    @Test
    void fxOnOneCustomerGivesTwoBalancesSortedByCurrency() {
        assertThat(deltas(
                credit(CUS_A, "AVAILABLE", 11000, "USD"),
                debit("int_FX_POSITION_USD", null, 11000, "USD"),
                debit(CUS_A, "AVAILABLE", 10000, "EUR"),
                credit("int_FX_POSITION_EUR", null, 10000, "EUR")))
                .containsExactly(
                        new BalanceDelta(CUS_A, "EUR", -10000, 0),
                        new BalanceDelta(CUS_A, "USD", 11000, 0));
    }

    @Test
    void severalEntriesOnOneBalanceNetToOneDelta() {
        assertThat(deltas(
                credit(CUS_A, "HELD", 300, "EUR"),
                credit(CUS_A, "AVAILABLE", 1000, "EUR"),
                debit(CUS_A, "AVAILABLE", 200, "EUR"),
                debit("int_SETTLEMENT_VISA", null, 1100, "EUR")))
                .containsExactly(new BalanceDelta(CUS_A, "EUR", 800, 300));
    }

    @Test
    void balancesAreSortedByAccountThenCurrency() {
        assertThat(deltas(
                credit(CUS_B, "AVAILABLE", 100, "EUR"),
                debit(CUS_A, "AVAILABLE", 100, "USD"),
                debit(CUS_A, "AVAILABLE", 100, "EUR"),
                credit(CUS_B, "AVAILABLE", 100, "USD")))
                .extracting(d -> d.accountId() + "/" + d.currency())
                .containsExactly(CUS_A + "/EUR", CUS_A + "/USD", CUS_B + "/EUR", CUS_B + "/USD");
    }

    @Test
    void internalOnlyPostingTouchesNoBalance() {
        assertThat(deltas(
                debit("int_SETTLEMENT_VISA", null, 5000, "EUR"),
                credit("int_BANK_NOSTRO_EUR", null, 5000, "EUR")))
                .isEmpty();
    }

    private static Iterable<BalanceDelta> deltas(EntryRequest... entries) {
        return BalanceDelta.of(PostingValidator.validate(posting(entries)).entries());
    }
}
