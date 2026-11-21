package ledger.journal.posting;

import static ledger.journal.posting.Fixtures.CUS_A;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(PostingController.class)
class PostingWebTest {

    private static final String VALID = """
            {"referenceId": "auth-1", "description": "card payment", "metadata": {"mcc": "5411"},
             "entries": [
               {"accountId": "%s", "bucket": "AVAILABLE", "direction": "DEBIT", "amount": 5000, "currency": "EUR"},
               {"accountId": "int_SETTLEMENT_VISA", "direction": "CREDIT", "amount": 5000, "currency": "EUR"}
             ]}
            """.formatted(CUS_A);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PostingRepository repository;

    @Test
    void validPostingIsCreated() throws Exception {
        UUID id = UUID.ofEpochMillis(System.currentTimeMillis());
        Instant now = Instant.parse("2026-11-20T10:15:30Z");
        given(repository.record(eq("key-1"), any())).willReturn(
                new RecordedPosting(id, now, now, List.of(new BalanceVersion(CUS_A, "EUR", 1))));

        send("key-1", VALID)
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.postedAt").value("2026-11-20T10:15:30Z"))
                .andExpect(jsonPath("$.referenceId").value("auth-1"))
                .andExpect(jsonPath("$.metadata.mcc").value("5411"))
                .andExpect(jsonPath("$.entries[0].line").value(1))
                .andExpect(jsonPath("$.entries[0].accountType").value("CUSTOMER"))
                .andExpect(jsonPath("$.entries[1].line").value(2))
                .andExpect(jsonPath("$.entries[1].accountType").value("INTERNAL"))
                .andExpect(jsonPath("$.entries[1].bucket").doesNotExist())
                .andExpect(jsonPath("$.versions[0].version").value(1));
    }

    @Test
    void unknownFieldIsMalformed() throws Exception {
        expectMalformed(VALID.replace("\"referenceId\"", "\"kind\": \"CARD\", \"referenceId\""));
    }

    @Test
    void fractionalAmountIsMalformed() throws Exception {
        expectMalformed(VALID.replace("\"amount\": 5000, \"currency\": \"EUR\"},", "\"amount\": 57.3, \"currency\": \"EUR\"},"));
    }

    @Test
    void amountAsStringIsMalformed() throws Exception {
        expectMalformed(VALID.replace("\"amount\": 5000, \"currency\": \"EUR\"},", "\"amount\": \"5000\", \"currency\": \"EUR\"},"));
    }

    @Test
    void missingDirectionIsMalformed() throws Exception {
        expectMalformed(VALID.replace("\"direction\": \"DEBIT\", ", ""));
    }

    @Test
    void unknownDirectionIsMalformed() throws Exception {
        expectMalformed(VALID.replace("\"DEBIT\"", "\"SIDEWAYS\""));
    }

    @Test
    void invalidJsonIsMalformed() throws Exception {
        expectMalformed("{\"entries\": [");
    }

    @Test
    void missingIdempotencyKey() throws Exception {
        mvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void emptyIdempotencyKeyIsMissing() throws Exception {
        send("", VALID)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void overlongIdempotencyKeyIsMalformed() throws Exception {
        send("k".repeat(256), VALID)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void entryErrorIdentifiesTheLine() throws Exception {
        send("key-1", VALID.replace("int_SETTLEMENT_VISA", "acct_42"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Unprocessable Content"))
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_ID"))
                .andExpect(jsonPath("$.line").value(2))
                .andExpect(jsonPath("$.detail").value(containsString("acct_42")));
    }

    @Test
    void unbalancedNamesCurrencyAndSums() throws Exception {
        send("key-1", VALID.replace("\"amount\": 5000, \"currency\": \"EUR\"}\n", "\"amount\": 4000, \"currency\": \"EUR\"}\n"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNBALANCED"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.debits").value(5000))
                .andExpect(jsonPath("$.credits").value(4000))
                .andExpect(jsonPath("$.line").doesNotExist());
    }

    private ResultActions send(String key, String body) throws Exception {
        return mvc.perform(post("/transactions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void expectMalformed(String body) throws Exception {
        send("key-1", body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
