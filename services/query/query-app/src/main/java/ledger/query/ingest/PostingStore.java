package ledger.query.ingest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import ledger.journal.events.TransactionPosted;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stores a posting and its account index rows atomically; a posting id already held changes nothing. */
@Component
public class PostingStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    PostingStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void store(TransactionPosted posting) {
        UUID id = UUID.fromString(posting.getId());
        OffsetDateTime occurredAt = utc(posting.getOccurredAt());
        Map<String, SortedSet<String>> currenciesByAccount = currenciesByAccount(posting.getEntries());

        int inserted = jdbc.sql("""
                INSERT INTO query.transaction (id, reference_id, occurred_at, posted_at, description,
                    metadata, entries, versions)
                VALUES (:id, :referenceId, :occurredAt, :postedAt, :description,
                    CAST(:metadata AS jsonb), CAST(:entries AS jsonb), CAST(:versions AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", id)
                .param("referenceId", posting.getReferenceId())
                .param("occurredAt", occurredAt)
                .param("postedAt", utc(posting.getPostedAt()))
                .param("description", posting.getDescription())
                .param("metadata", posting.getMetadata())
                .param("entries", posting.getEntries())
                .param("versions", posting.getVersions())
                .update();
        if (inserted == 0) {
            return;
        }
        currenciesByAccount.forEach((accountId, currencies) -> jdbc.sql("""
                INSERT INTO query.account_transaction (account_id, transaction_id, occurred_at, currencies)
                VALUES (:accountId, :id, :occurredAt, CAST(:currencies AS char(3)[]))
                """)
                .param("accountId", accountId)
                .param("id", id)
                .param("occurredAt", occurredAt)
                .param("currencies", currencies.toArray(String[]::new))
                .update());
    }

    private Map<String, SortedSet<String>> currenciesByAccount(String entries) {
        JsonNode array = json.readTree(entries);
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException("Posting entries must be a non-empty JSON array");
        }
        Map<String, SortedSet<String>> result = new LinkedHashMap<>();
        for (JsonNode entry : array) {
            String accountId = entry.required("accountId").asString();
            String currency = entry.required("currency").asString();
            result.computeIfAbsent(accountId, ignored -> new TreeSet<>()).add(currency);
        }
        return result;
    }

    private static OffsetDateTime utc(String timestamp) {
        return OffsetDateTime.parse(timestamp).toInstant().atOffset(ZoneOffset.UTC);
    }

}
