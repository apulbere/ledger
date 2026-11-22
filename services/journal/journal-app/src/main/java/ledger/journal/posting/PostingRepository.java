package ledger.journal.posting;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
class PostingRepository {

    private static final String UPSERT_BALANCE = """
            INSERT INTO journal.balance
                (account_id, currency, available_debit, available_credit, held_debit, held_credit,
                 version, last_transaction_id)
            VALUES (?, ?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT (account_id, currency) DO UPDATE
               SET available_debit = balance.available_debit + EXCLUDED.available_debit,
                   available_credit = balance.available_credit + EXCLUDED.available_credit,
                   held_debit = balance.held_debit + EXCLUDED.held_debit,
                   held_credit = balance.held_credit + EXCLUDED.held_credit,
                   version = balance.version + 1,
                   last_transaction_id = EXCLUDED.last_transaction_id
            RETURNING version
            """;

    private static final String INSERT_TRANSACTION = """
            INSERT INTO journal.transaction
                (id, idempotency_key, reference_id, occurred_at, description, metadata, entries, versions)
            VALUES (?, ?, ?, COALESCE(?::timestamptz, now()), ?, ?::jsonb, ?::jsonb, ?::jsonb)
            RETURNING occurred_at, posted_at
            """;

    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "transaction_idempotency_key_uk";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;

    PostingRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbcClient, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Applies the posting's balance changes and appends it to the log in one DB transaction. Balance rows stay
     * locked until commit; a reused idempotency key rolls everything back.
     */
    @Transactional
    RecordedPosting record(String idempotencyKey, ValidatedPosting posting) {
        UUID id = UUID.ofEpochMillis(System.currentTimeMillis());
        List<BalanceVersion> versions = upsertBalances(id, BalanceDelta.of(posting.entries()));
        try {
            return jdbcClient.sql(INSERT_TRANSACTION)
                    .params(id, idempotencyKey, posting.referenceId(), posting.occurredAt(), posting.description(),
                            json(posting.metadata()), json(posting.entries()), json(versions))
                    .query((rs, n) -> new RecordedPosting(id,
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("posted_at", OffsetDateTime.class).toInstant(),
                            versions))
                    .single();
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains(IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw PostingProblem.idempotencyKeyReused();
            }
            throw e;
        }
    }

    // One JDBC batch, items in lock order: the driver runs them in order, so locks are taken in that order.
    // reWriteBatchedInserts must stay off, as it would merge the items into one multi-row INSERT.
    private List<BalanceVersion> upsertBalances(UUID transactionId, List<BalanceDelta> deltas) {
        if (deltas.isEmpty()) {
            return List.of();
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.batchUpdate(
                con -> con.prepareStatement(UPSERT_BALANCE, new String[] {"version"}),
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        BalanceDelta delta = deltas.get(i);
                        ps.setString(1, delta.accountId());
                        ps.setString(2, delta.currency());
                        ps.setLong(3, delta.availableDebit());
                        ps.setLong(4, delta.availableCredit());
                        ps.setLong(5, delta.heldDebit());
                        ps.setLong(6, delta.heldCredit());
                        ps.setObject(7, transactionId);
                    }

                    @Override
                    public int getBatchSize() {
                        return deltas.size();
                    }
                },
                keys);
        List<Map<String, Object>> rows = keys.getKeyList();
        if (rows.size() != deltas.size()) {
            throw new IllegalStateException("Expected " + deltas.size() + " balance versions, got " + rows.size());
        }
        List<BalanceVersion> versions = new ArrayList<>(deltas.size());
        for (int i = 0; i < deltas.size(); i++) {
            BalanceDelta delta = deltas.get(i);
            long version = ((Number) rows.get(i).get("version")).longValue();
            versions.add(new BalanceVersion(delta.accountId(), delta.currency(), version));
        }
        return versions;
    }

    private String json(Object value) {
        return value == null ? null : jsonMapper.writeValueAsString(value);
    }
}
