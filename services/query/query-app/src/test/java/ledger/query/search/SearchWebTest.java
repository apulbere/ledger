package ledger.query.search;

import static ledger.query.Fixtures.credit;
import static ledger.query.Fixtures.customer;
import static ledger.query.Fixtures.debit;
import static ledger.query.Fixtures.held;
import static ledger.query.Fixtures.internal;
import static ledger.query.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import ledger.journal.events.TransactionPosted;
import ledger.query.TestcontainersConfiguration;
import ledger.query.ingest.PostingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Search scenarios of query/transaction-search, with postings stored the way ingestion stores them. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchWebTest {

    private static final Instant DAY = Instant.parse("2026-11-21T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    PostingStore store;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static Instant at(int hour) {
        return DAY.plusSeconds(hour * 3600L);
    }

    private TransactionPosted stored(TransactionPosted posting) {
        store.store(posting);
        return posting;
    }

    private JsonNode search(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andDo(OpenApiContract.conforms()).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder transactions(String accountId) {
        return get("/accounts/{accountId}/transactions", accountId);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    @Test
    void customerAccountSearch() throws Exception {
        String a = customer(), b = customer(), visa = internal(), nostro = internal();
        TransactionPosted t1 = stored(posting(at(1), debit(a, 5000, "EUR"), credit(visa, 5000, "EUR")));
        stored(posting(at(2), debit(b, 5000, "EUR"), credit(nostro, 5000, "EUR")));

        JsonNode page = search(transactions(a));

        assertThat(ids(page)).containsExactly(t1.getId());
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void internalAccountSearch() throws Exception {
        String visa = internal();
        TransactionPosted t1 = stored(posting(at(1), debit(customer(), 5000, "EUR"), credit(visa, 5000, "EUR")));
        TransactionPosted t3 = stored(posting(at(3), debit(customer(), 700, "EUR"), credit(visa, 700, "EUR")));

        assertThat(ids(search(transactions(visa)))).containsExactly(t3.getId(), t1.getId());
    }

    @Test
    void severalEntriesOnTheAccountGiveOneItem() throws Exception {
        String a = customer();
        TransactionPosted hold = stored(posting(at(1), debit(a, 5730, "EUR"), held(credit(a, 5730, "EUR"))));

        assertThat(ids(search(transactions(a)))).containsExactly(hold.getId());
    }

    @Test
    void accountWithNoPostings() throws Exception {
        JsonNode page = search(transactions(customer()));

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void itemCarriesEveryEntryOfThePosting() throws Exception {
        String a = customer();
        TransactionPosted fx = stored(posting(at(1),
                debit(a, 10000, "EUR"), credit(internal(), 10000, "EUR"),
                debit(internal(), 11000, "USD"), credit(a, 11000, "USD")));

        JsonNode item = search(transactions(a)).get("items").get(0);

        assertThat(item.get("entries")).isEqualTo(JSON.readTree(fx.getEntries()));
        assertThat(item.get("versions")).isEqualTo(JSON.readTree(fx.getVersions()));
    }

    @Test
    void newestOccurrenceFirstThenIdDescending() throws Exception {
        String a = customer(), visa = internal();
        TransactionPosted t1 = stored(posting(at(10), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));
        TransactionPosted t2 = stored(posting(at(34), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));
        // Same occurredAt as t2, later (higher) id: ties are broken by id, descending.
        TransactionPosted t3 = stored(posting(at(34), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));

        assertThat(ids(search(transactions(a)))).containsExactly(t3.getId(), t2.getId(), t1.getId());
    }

    @Test
    void backdatedPostingSortsByOccurrence() throws Exception {
        String a = customer(), visa = internal();
        TransactionPosted t1 = stored(posting(at(10), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));
        // Recorded after t1, occurred before it.
        TransactionPosted t2 = stored(posting(at(5), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));

        assertThat(ids(search(transactions(a)))).containsExactly(t1.getId(), t2.getId());
    }

    @Test
    void currencyFilterMatchesEitherCurrencyOfAnFxPosting() throws Exception {
        String a = customer();
        TransactionPosted fx = stored(posting(at(2),
                debit(a, 10000, "EUR"), credit(internal(), 10000, "EUR"),
                debit(internal(), 11000, "USD"), credit(a, 11000, "USD")));
        TransactionPosted eurOnly = stored(posting(at(1), debit(a, 1, "EUR"), credit(internal(), 1, "EUR")));

        assertThat(ids(search(transactions(a).param("currency", "USD")))).containsExactly(fx.getId());
        assertThat(ids(search(transactions(a).param("currency", "EUR")))).containsExactly(fx.getId(), eurOnly.getId());
    }

    @Test
    void currencyOfAnotherAccountDoesNotMatch() throws Exception {
        String a = customer();
        stored(posting(at(1),
                debit(a, 10000, "EUR"), credit(internal(), 10000, "EUR"),
                debit(internal(), 11000, "USD"), credit(internal(), 11000, "USD")));

        assertThat(ids(search(transactions(a).param("currency", "USD")))).isEmpty();
    }

    @Test
    void occurrenceRangeIsInclusiveExclusive() throws Exception {
        String a = customer(), visa = internal();
        TransactionPosted t10 = stored(posting(at(10), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));
        TransactionPosted t11 = stored(posting(at(11), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));
        stored(posting(at(12), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));

        JsonNode page = search(transactions(a)
                .param("occurredFrom", "2026-11-21T10:00:00Z")
                .param("occurredTo", "2026-11-21T12:00:00Z"));

        assertThat(ids(page)).containsExactly(t11.getId(), t10.getId());
        assertThat(ids(search(transactions(a).param("occurredTo", "2026-11-21T12:00:00+01:00"))))
                .containsExactly(t10.getId());
    }

    @Test
    void pagingWalksEveryPostingOnce() throws Exception {
        String a = customer(), visa = internal();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            expected.addFirst(stored(posting(at(i), debit(a, 1, "EUR"), credit(visa, 1, "EUR"))).getId());
        }

        List<Integer> sizes = new ArrayList<>();
        List<String> read = new ArrayList<>();
        String cursor = null;
        do {
            MockHttpServletRequestBuilder request = transactions(a).param("limit", "20");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            JsonNode page = search(request);
            sizes.add(page.get("items").size());
            read.addAll(ids(page));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);

        assertThat(sizes).containsExactly(20, 20, 5);
        assertThat(read).containsExactlyElementsOf(expected);
        assertThat(new HashSet<>(read)).hasSize(45);
    }

    @Test
    void defaultLimitIsTwenty() throws Exception {
        String a = customer(), visa = internal();
        for (int i = 0; i < 25; i++) {
            stored(posting(at(i), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));
        }

        JsonNode page = search(transactions(a));

        assertThat(page.get("items")).hasSize(20);
        assertThat(page.get("nextCursor").isNull()).isFalse();
    }

    @Test
    void exactlyFullPageHasNoNextCursor() throws Exception {
        String a = customer(), visa = internal();
        for (int i = 0; i < 20; i++) {
            stored(posting(at(i), debit(a, 1, "EUR"), credit(visa, 1, "EUR")));
        }

        JsonNode page = search(transactions(a).param("limit", "20"));

        assertThat(page.get("items")).hasSize(20);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        String a = customer();
        rejected(transactions("acct_42"), "accountId", OpenApiContract.rejectedBySchema());
        rejected(transactions("cus_42"), "accountId", OpenApiContract.rejectedBySchema());
        rejected(transactions(a).param("currency", "eur"), "currency", OpenApiContract.rejectedBySchema());
        rejected(transactions(a).param("occurredFrom", "yesterday"), "occurredFrom",
                OpenApiContract.rejectedBySchema());
        rejected(transactions(a).param("occurredTo", "2026-11-21"), "occurredTo", OpenApiContract.rejectedBySchema());
        rejected(transactions(a).param("limit", "0"), "limit", OpenApiContract.rejectedBySchema());
        rejected(transactions(a).param("limit", "101"), "limit", OpenApiContract.rejectedBySchema());
        rejected(transactions(a).param("limit", "ten"), "limit", OpenApiContract.rejectedBySchema());
        // Valid per the schema, rejected by the API.
        rejected(transactions(a).param("cursor", "not-a-cursor"), "cursor", OpenApiContract.conforms());
        rejected(transactions(a)
                .param("occurredFrom", "2026-11-21T12:00:00Z")
                .param("occurredTo", "2026-11-21T12:00:00Z"), "occurredTo", OpenApiContract.conforms());
    }

    private void rejected(MockHttpServletRequestBuilder request, String parameter, ResultHandler contract)
            throws Exception {
        MvcResult result = mvc.perform(request).andDo(contract).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/problem+json");
        JsonNode problem = JSON.readTree(body);
        assertThat(problem.get("code").asString()).isEqualTo("INVALID_PARAMETER");
        assertThat(problem.get("parameter").asString()).isEqualTo(parameter);
    }

}
