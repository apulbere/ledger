package ledger.query.search;

import static ledger.query.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.InputStream;
import java.time.Instant;
import ledger.journal.events.TransactionPosted;
import ledger.query.TestcontainersConfiguration;
import ledger.query.events.JournalEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A posting found by search equals the journal's 201 response for it, after the full event path. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecordedPostingSearchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    JournalEvents journal;

    @Test
    void itemEqualsTheJournalsRecordedPosting() throws Exception {
        ObjectNode recorded;
        try (InputStream in = getClass().getResourceAsStream("/journal-fx-posting-201.json")) {
            recorded = (ObjectNode) JSON.readTree(in);
        }
        // The event as the connector publishes the journal row for that response: JSON columns as text.
        journal.posting(TransactionPosted.newBuilder()
                .setId(recorded.get("id").asString())
                .setIdempotencyKey("fx-key-0007")
                .setReferenceId(recorded.get("referenceId").asString())
                .setOccurredAt(recorded.get("occurredAt").asString())
                .setPostedAt(recorded.get("postedAt").asString())
                .setDescription(recorded.get("description").asString())
                .setMetadata(JSON.writeValueAsString(recorded.get("metadata")))
                .setEntries(JSON.writeValueAsString(recorded.get("entries")))
                .setVersions(JSON.writeValueAsString(recorded.get("versions")))
                .build());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        for (String account : new String[] {"cus_01jcz8k2m4f7r9t3vbx5w6y8za", "int_FX_POSITION_USD"}) {
            eventually(() -> {
                String body = mvc.perform(get("/accounts/{id}/transactions", account))
                        .andDo(OpenApiContract.conforms())
                        .andReturn().getResponse().getContentAsString();
                JsonNode item = null;
                for (JsonNode candidate : JSON.readTree(body).get("items")) {
                    if (candidate.get("id").equals(recorded.get("id"))) {
                        item = candidate;
                    }
                }
                assertThat(item).as(body).isNotNull();
                assertEqualsWithTimestampsAsInstants(item, recorded);
            });
        }
    }

    private static void assertEqualsWithTimestampsAsInstants(JsonNode item, ObjectNode recorded) {
        ObjectNode actual = ((ObjectNode) item).deepCopy();
        ObjectNode expected = recorded.deepCopy();
        for (String field : new String[] {"occurredAt", "postedAt"}) {
            assertThat(Instant.parse(actual.remove(field).asString()))
                    .as(field).isEqualTo(Instant.parse(expected.remove(field).asString()));
        }
        assertThat(actual).isEqualTo(expected);
    }
}
