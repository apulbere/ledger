package ledger.journal.posting;

import static ledger.journal.posting.Fixtures.credit;
import static ledger.journal.posting.Fixtures.debit;
import static ledger.journal.posting.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PostingIntegrationTest extends PostingApiSupport {

    @Test
    void firstPostingCreatesTheBalance() {
        String a = newCustomer();

        JsonNode recorded = record(posting(
                credit(a, "AVAILABLE", 5000, "EUR"),
                debit("int_SETTLEMENT_VISA", null, 5000, "EUR")));

        UUID id = UUID.fromString(recorded.get("id").asString());
        assertThat(id.version()).isEqualTo(7);
        assertThat(balance(a, "EUR")).contains(new Balance(5000, 0, 1, id));
        assertThat(versions(recorded)).containsExactly(a + "/EUR/1");
    }

    @Test
    void currenciesAreIndependent() {
        String a = newCustomer();

        record(posting(credit(a, "AVAILABLE", 5000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 5000, "EUR")));
        record(posting(credit(a, "AVAILABLE", 11000, "USD"), debit("int_BANK_NOSTRO_USD", null, 11000, "USD")));

        assertThat(balance(a, "EUR")).hasValueSatisfying(b -> assertThat(b.available()).isEqualTo(5000));
        assertThat(balance(a, "USD")).hasValueSatisfying(b -> assertThat(b.available()).isEqualTo(11000));
    }

    @Test
    void holdMovesMoneyBetweenBuckets() {
        String a = newCustomer();
        record(posting(credit(a, "AVAILABLE", 10000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 10000, "EUR")));

        JsonNode hold = record(posting(debit(a, "AVAILABLE", 5000, "EUR"), credit(a, "HELD", 5000, "EUR")));

        assertThat(balance(a, "EUR")).contains(new Balance(5000, 5000, 2, id(hold)));
        assertThat(versions(hold)).containsExactly(a + "/EUR/2");
    }

    @Test
    void fxOnOneCustomerChangesTwoBalances() {
        String a = newCustomer();
        record(posting(credit(a, "AVAILABLE", 45000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 45000, "EUR")));
        record(posting(credit(a, "AVAILABLE", 2000, "USD"), debit("int_BANK_NOSTRO_USD", null, 2000, "USD")));

        JsonNode fx = record(posting(
                debit(a, "AVAILABLE", 10000, "EUR"),
                credit("int_FX_POSITION_EUR", null, 10000, "EUR"),
                debit("int_FX_POSITION_USD", null, 11000, "USD"),
                credit(a, "AVAILABLE", 11000, "USD")));

        assertThat(balance(a, "EUR")).contains(new Balance(35000, 0, 2, id(fx)));
        assertThat(balance(a, "USD")).contains(new Balance(13000, 0, 2, id(fx)));
        assertThat(versions(fx)).containsExactly(a + "/EUR/2", a + "/USD/2");
    }

    @Test
    void transferPlusFeeVersionsEachBalance() {
        String a = newCustomer();
        String b = newCustomer();
        String c = newCustomer();
        record(posting(credit(a, "AVAILABLE", 3000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 3000, "EUR")));
        record(posting(credit(a, "AVAILABLE", 2000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 2000, "EUR")));
        record(posting(credit(b, "AVAILABLE", 100, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 100, "EUR")));

        JsonNode transfer = record(posting(
                debit(a, "AVAILABLE", 1050, "EUR"),
                credit(b, "AVAILABLE", 1000, "EUR"),
                credit(c, "AVAILABLE", 50, "EUR")));

        assertThat(balance(a, "EUR")).contains(new Balance(3950, 0, 3, id(transfer)));
        assertThat(balance(b, "EUR")).contains(new Balance(1100, 0, 2, id(transfer)));
        assertThat(balance(c, "EUR")).contains(new Balance(50, 0, 1, id(transfer)));
        assertThat(versions(transfer)).containsExactlyInAnyOrder(a + "/EUR/3", b + "/EUR/2", c + "/EUR/1");
    }

    @Test
    void balancesMayGoNegative() {
        String a = newCustomer();
        record(posting(credit(a, "AVAILABLE", 1000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 1000, "EUR")));

        record(posting(debit(a, "AVAILABLE", 5000, "EUR"), credit("int_SETTLEMENT_VISA", null, 5000, "EUR")));

        assertThat(balance(a, "EUR")).hasValueSatisfying(b -> assertThat(b.available()).isEqualTo(-4000));
    }

    @Test
    void internalOnlyPostingChangesNoBalance() {
        JsonNode recorded = record(posting(
                debit("int_SETTLEMENT_VISA", null, 5000, "EUR"),
                credit("int_BANK_NOSTRO_EUR", null, 5000, "EUR")));

        assertThat(recorded.get("versions").isEmpty()).isTrue();
        assertThat(balance("int_SETTLEMENT_VISA", "EUR")).isEmpty();
        assertThat(balance("int_BANK_NOSTRO_EUR", "EUR")).isEmpty();
    }

    @Test
    void severalEntriesOnOneBalanceIncrementTheVersionOnce() {
        String a = newCustomer();
        record(posting(credit(a, "AVAILABLE", 1000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 1000, "EUR")));

        JsonNode recorded = record(posting(
                credit(a, "HELD", 300, "EUR"),
                credit(a, "AVAILABLE", 200, "EUR"),
                debit(a, "AVAILABLE", 100, "EUR"),
                debit("int_SETTLEMENT_VISA", null, 400, "EUR")));

        assertThat(balance(a, "EUR")).contains(new Balance(1100, 300, 2, id(recorded)));
        assertThat(versions(recorded)).containsExactly(a + "/EUR/2");
    }

    @Test
    void versionsAreContiguous() {
        String a = newCustomer();
        for (int i = 0; i < 3; i++) {
            record(posting(credit(a, "AVAILABLE", 100, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 100, "EUR")));
        }

        assertThat(recordedVersions(a, "EUR")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void untouchedBalanceKeepsItsLastPosting() {
        String a = newCustomer();
        String b = newCustomer();
        JsonNode first = record(posting(credit(a, "AVAILABLE", 100, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 100, "EUR")));

        JsonNode second = record(posting(credit(b, "AVAILABLE", 100, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 100, "EUR")));

        assertThat(balance(a, "EUR").orElseThrow().lastTransactionId()).isEqualTo(id(first));
        assertThat(balance(b, "EUR").orElseThrow().lastTransactionId()).isEqualTo(id(second));
    }

    @Test
    void omittedOccurredAtEqualsPostedAt() {
        JsonNode recorded = record(posting(
                debit("int_SETTLEMENT_VISA", null, 5000, "EUR"),
                credit("int_BANK_NOSTRO_EUR", null, 5000, "EUR")));

        assertThat(recorded.get("occurredAt").asString()).isEqualTo(recorded.get("postedAt").asString());
        assertThat(jdbc.sql("SELECT occurred_at = posted_at FROM journal.transaction WHERE id = ?")
                .param(id(recorded)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void givenOccurredAtIsKept() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-11-19T23:30:00+02:00");

        JsonNode recorded = record(new PostingRequest("ref", occurredAt, "late settlement", null, List.of(
                debit("int_SETTLEMENT_VISA", null, 5000, "EUR"),
                credit("int_BANK_NOSTRO_EUR", null, 5000, "EUR"))));

        assertThat(Instant.parse(recorded.get("occurredAt").asString())).isEqualTo(occurredAt.toInstant());
        assertThat(recorded.get("description").asString()).isEqualTo("late settlement");
    }

    @Test
    void referenceIdIsSharedByRelatedPostings() {
        String referenceId = "auth-" + newKey();
        PostingRequest request = new PostingRequest(referenceId, null, null, null, List.of(
                debit("int_SETTLEMENT_VISA", null, 5000, "EUR"),
                credit("int_BANK_NOSTRO_EUR", null, 5000, "EUR")));

        record(request);
        record(request);

        assertThat(jdbc.sql("SELECT count(*) FROM journal.transaction WHERE reference_id = ?")
                .param(referenceId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void rejectedPostingLeavesBalancesUntouched() {
        String a = newCustomer();
        record(posting(credit(a, "AVAILABLE", 1000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 1000, "EUR")));
        Balance before = balance(a, "EUR").orElseThrow();
        String key = newKey();

        Response response = post(key, posting(debit(a, "AVAILABLE", 5730, "EUR"), credit("int_SETTLEMENT_VISA", null, 5719, "EUR")));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body().get("code").asString()).isEqualTo("UNBALANCED");
        assertThat(balance(a, "EUR")).contains(before);
        assertThat(postingsWithKey(key)).isZero();
    }

    @Test
    void retryWithSameBodyIsRejected() {
        String a = newCustomer();
        String key = newKey();
        PostingRequest request = posting(credit(a, "AVAILABLE", 1000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 1000, "EUR"));

        Response first = post(key, request);
        Balance afterFirst = balance(a, "EUR").orElseThrow();
        Response retry = post(key, request);

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(409);
        assertThat(retry.body().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(postingsWithKey(key)).isEqualTo(1);
        assertThat(balance(a, "EUR")).contains(afterFirst);
        assertThat(recordedVersions(a, "EUR")).containsExactly(1L);
    }

    @Test
    void keyReusedWithDifferentBodyIsRejected() {
        String a = newCustomer();
        String key = newKey();
        post(key, posting(credit(a, "AVAILABLE", 1000, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 1000, "EUR")));
        Balance afterFirst = balance(a, "EUR").orElseThrow();

        Response reused = post(key, posting(credit(a, "AVAILABLE", 9999, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 9999, "EUR")));

        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.body().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(postingsWithKey(key)).isEqualTo(1);
        assertThat(balance(a, "EUR")).contains(afterFirst);
    }

    private static UUID id(JsonNode posting) {
        return UUID.fromString(posting.get("id").asString());
    }

    private static List<String> versions(JsonNode posting) {
        return posting.get("versions").valueStream()
                .map(v -> v.get("accountId").asString() + "/" + v.get("currency").asString() + "/" + v.get("version").asLong())
                .toList();
    }
}
