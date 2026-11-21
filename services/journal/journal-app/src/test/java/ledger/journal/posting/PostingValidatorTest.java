package ledger.journal.posting;

import static ledger.journal.posting.Fixtures.CUS_A;
import static ledger.journal.posting.Fixtures.credit;
import static ledger.journal.posting.Fixtures.debit;
import static ledger.journal.posting.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import ledger.journal.posting.PostingRequest.EntryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class PostingValidatorTest {

    @Test
    void validPostingIsNumberedAndTyped() {
        ValidatedPosting posting = PostingValidator.validate(posting(
                debit(CUS_A, "AVAILABLE", 5000, "EUR"),
                credit("int_SETTLEMENT_VISA", null, 5000, "EUR")));

        assertThat(posting.entries()).containsExactly(
                new Entry(1, CUS_A, AccountType.CUSTOMER, Bucket.AVAILABLE, Direction.DEBIT, 5000, "EUR"),
                new Entry(2, "int_SETTLEMENT_VISA", AccountType.INTERNAL, null, Direction.CREDIT, 5000, "EUR"));
    }

    @Test
    void invalidAccountIdNamesTheLine() {
        PostingProblem problem = reject(posting(
                debit(CUS_A, "AVAILABLE", 5000, "EUR"),
                credit("acct_42", null, 5000, "EUR")));

        assertProblem(problem, "INVALID_ACCOUNT_ID", 2);
    }

    @Test
    void customerEntryWithoutBucket() {
        assertProblem(reject(posting(
                debit(CUS_A, null, 5000, "EUR"),
                credit("int_SETTLEMENT_VISA", null, 5000, "EUR"))), "INVALID_BUCKET", 1);
    }

    @Test
    void internalEntryWithBucket() {
        assertProblem(reject(posting(
                debit(CUS_A, "AVAILABLE", 5000, "EUR"),
                credit("int_SETTLEMENT_VISA", "AVAILABLE", 5000, "EUR"))), "INVALID_BUCKET", 2);
    }

    @Test
    void unknownBucketValue() {
        assertProblem(reject(posting(
                debit(CUS_A, "PENDING", 5000, "EUR"),
                credit("int_SETTLEMENT_VISA", null, 5000, "EUR"))), "INVALID_BUCKET", 1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100, 1_000_000_000_000_001L})
    void amountOutOfRange(long amount) {
        assertProblem(reject(posting(
                debit(CUS_A, "AVAILABLE", amount, "EUR"),
                credit("int_SETTLEMENT_VISA", null, amount, "EUR"))), "INVALID_AMOUNT", 1);
    }

    @Test
    void maximumAmountAccepted() {
        PostingValidator.validate(posting(
                debit(CUS_A, "AVAILABLE", 1_000_000_000_000_000L, "EUR"),
                credit("int_SETTLEMENT_VISA", null, 1_000_000_000_000_000L, "EUR")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "eur", "XAU", "XXX"})
    void unknownCurrency(String currency) {
        assertProblem(reject(posting(
                debit(CUS_A, "AVAILABLE", 5000, currency),
                credit("int_SETTLEMENT_VISA", null, 5000, currency))), "UNKNOWN_CURRENCY", 1);
    }

    @Test
    void zeroDecimalCurrencyAccepted() {
        PostingValidator.validate(posting(
                debit(CUS_A, "AVAILABLE", 500, "JPY"),
                credit("int_SETTLEMENT_VISA", null, 500, "JPY")));
    }

    @Test
    void singleEntry() {
        assertProblem(reject(posting(debit(CUS_A, "AVAILABLE", 5000, "EUR"))), "TOO_FEW_ENTRIES", null);
    }

    @Test
    void tooManyEntries() {
        EntryRequest entry = debit(CUS_A, "AVAILABLE", 1, "EUR");
        PostingRequest request = new PostingRequest(null, null, null, null, Collections.nCopies(101, entry));

        assertProblem(reject(request), "TOO_MANY_ENTRIES", null);
    }

    @Test
    void unbalancedNamesCurrencyAndSums() {
        PostingProblem problem = reject(posting(
                debit(CUS_A, "AVAILABLE", 5730, "EUR"),
                credit("int_SETTLEMENT_VISA", null, 5719, "EUR")));

        assertProblem(problem, "UNBALANCED", null);
        assertThat(problem.properties())
                .isEqualTo(Map.of("currency", "EUR", "debits", 5730L, "credits", 5719L));
    }

    @Test
    void multiCurrencyBalancedPerCurrencyAccepted() {
        ValidatedPosting posting = PostingValidator.validate(posting(
                debit(CUS_A, "AVAILABLE", 10000, "EUR"),
                credit("int_FX_POSITION_EUR", null, 10000, "EUR"),
                debit("int_FX_POSITION_USD", null, 11000, "USD"),
                credit(CUS_A, "AVAILABLE", 11000, "USD")));

        assertThat(posting.entries()).hasSize(4);
    }

    @Test
    void balancedOverallButNotPerCurrencyRejected() {
        assertProblem(reject(posting(
                debit(CUS_A, "AVAILABLE", 5000, "EUR"),
                credit("int_SETTLEMENT_VISA", null, 5000, "USD"))), "UNBALANCED", null);
    }

    @Test
    void missingRequiredFieldIsMalformed() {
        PostingProblem problem = reject(posting(
                new EntryRequest(CUS_A, "AVAILABLE", null, 5000L, "EUR"),
                credit("int_SETTLEMENT_VISA", null, 5000, "EUR")));

        assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problem.code()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void overlongEnvelopeFieldsAreMalformed() {
        List<EntryRequest> entries = List.of(
                debit(CUS_A, "AVAILABLE", 5000, "EUR"), credit("int_SETTLEMENT_VISA", null, 5000, "EUR"));

        assertThat(reject(new PostingRequest("r".repeat(129), null, null, null, entries)).code())
                .isEqualTo("MALFORMED_REQUEST");
        assertThat(reject(new PostingRequest(null, null, "d".repeat(501), null, entries)).code())
                .isEqualTo("MALFORMED_REQUEST");
        PostingValidator.validate(new PostingRequest("r".repeat(128), null, "d".repeat(500), null, entries));
    }

    private static PostingProblem reject(PostingRequest request) {
        return catchThrowableOfType(PostingProblem.class, () -> PostingValidator.validate(request));
    }

    private static void assertProblem(PostingProblem problem, String code, Integer line) {
        assertThat(problem).as("expected %s", code).isNotNull();
        assertThat(problem.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(problem.code()).isEqualTo(code);
        assertThat(problem.line()).isEqualTo(line);
    }
}
