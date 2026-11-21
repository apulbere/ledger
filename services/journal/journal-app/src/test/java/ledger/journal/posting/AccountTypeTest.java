package ledger.journal.posting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"cus_01jcz8k2m4f7r9t3vbx5w6y8za", "cus_7zzzzzzzzzzzzzzzzzzzzzzzzz"})
    void customerIds(String id) {
        assertThat(AccountType.of(id)).contains(AccountType.CUSTOMER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"int_SETTLEMENT_VISA", "int_FX_POSITION_EUR", "int_ABC"})
    void internalIds(String id) {
        assertThat(AccountType.of(id)).contains(AccountType.INTERNAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "acct_42", "cus_42", "int_settlement",
            "cus_81jcz8k2m4f7r9t3vbx5w6y8za", // first suffix character above 7
            "cus_01jcz8k2m4f7r9t3vbx5w6y8zu", // u is not Crockford base32
            "cus_01JCZ8K2M4F7R9T3VBX5W6Y8ZA", // uppercase
            "int_AB", "int_1ABC", ""})
    void rejectedIds(String id) {
        assertThat(AccountType.of(id)).isEmpty();
    }
}
