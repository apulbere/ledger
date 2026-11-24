package ledger.query.search;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Keyset-paged search over the account index, newest occurrence first. */
@Component
class TransactionSearch {

    record Posting(String id, String referenceId, String occurredAt, String postedAt, String description,
            JsonNode metadata, JsonNode entries, JsonNode versions) {
    }

    record Page(List<Posting> items, String nextCursor) {
    }

    private record Row(Posting posting, Cursor position) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    TransactionSearch(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Page search(SearchRequest request) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.reference_id, t.occurred_at, t.posted_at, t.description,
                       t.metadata::text AS metadata, t.entries::text AS entries, t.versions::text AS versions
                FROM query.account_transaction a
                JOIN query.transaction t ON t.id = a.transaction_id
                WHERE a.account_id = :accountId
                """);
        if (request.currency() != null) {
            sql.append(" AND a.currencies @> ARRAY[CAST(:currency AS char(3))]");
        }
        if (request.occurredFrom() != null) {
            sql.append(" AND a.occurred_at >= :occurredFrom");
        }
        if (request.occurredTo() != null) {
            sql.append(" AND a.occurred_at < :occurredTo");
        }
        if (request.after() != null) {
            sql.append(" AND (a.occurred_at, a.transaction_id) < (:afterOccurredAt, :afterId)");
        }
        sql.append(" ORDER BY a.occurred_at DESC, a.transaction_id DESC LIMIT :fetch");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("accountId", request.accountId())
                .param("fetch", request.limit() + 1);
        if (request.currency() != null) {
            statement = statement.param("currency", request.currency());
        }
        if (request.occurredFrom() != null) {
            statement = statement.param("occurredFrom", request.occurredFrom().atOffset(ZoneOffset.UTC));
        }
        if (request.occurredTo() != null) {
            statement = statement.param("occurredTo", request.occurredTo().atOffset(ZoneOffset.UTC));
        }
        if (request.after() != null) {
            statement = statement
                    .param("afterOccurredAt", request.after().occurredAt().atOffset(ZoneOffset.UTC))
                    .param("afterId", request.after().id());
        }

        List<Row> rows = statement.query((rs, i) -> {
            Instant occurredAt = rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant();
            UUID id = rs.getObject("id", UUID.class);
            String metadata = rs.getString("metadata");
            return new Row(new Posting(
                    id.toString(),
                    rs.getString("reference_id"),
                    occurredAt.toString(),
                    rs.getObject("posted_at", java.time.OffsetDateTime.class).toInstant().toString(),
                    rs.getString("description"),
                    metadata == null ? null : json.readTree(metadata),
                    json.readTree(rs.getString("entries")),
                    json.readTree(rs.getString("versions"))),
                    new Cursor(occurredAt, id));
        }).list();

        boolean more = rows.size() > request.limit();
        List<Row> page = more ? rows.subList(0, request.limit()) : rows;
        return new Page(page.stream().map(Row::posting).toList(),
                more ? page.getLast().position().encode() : null);
    }
}
