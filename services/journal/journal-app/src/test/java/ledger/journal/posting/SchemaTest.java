package ledger.journal.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import ledger.journal.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void transactionTableHasExpectedColumns() {
        assertThat(columns("transaction")).containsExactly(
                Map.entry("id", "uuid:NO"),
                Map.entry("idempotency_key", "text:NO"),
                Map.entry("reference_id", "text:YES"),
                Map.entry("occurred_at", "timestamp with time zone:NO"),
                Map.entry("posted_at", "timestamp with time zone:NO"),
                Map.entry("description", "text:YES"),
                Map.entry("metadata", "jsonb:YES"),
                Map.entry("entries", "jsonb:NO"),
                Map.entry("versions", "jsonb:NO"));
        assertThat(constraintColumns("transaction", "PRIMARY KEY")).containsExactly("id");
        assertThat(constraintColumns("transaction", "UNIQUE")).containsExactly("idempotency_key");
    }

    @Test
    void balanceTableHasExpectedColumns() {
        assertThat(columns("balance")).containsExactly(
                Map.entry("account_id", "text:NO"),
                Map.entry("currency", "character:NO"),
                Map.entry("available", "bigint:NO"),
                Map.entry("held", "bigint:NO"),
                Map.entry("version", "bigint:NO"),
                Map.entry("last_transaction_id", "uuid:NO"));
        assertThat(constraintColumns("balance", "PRIMARY KEY")).containsExactly("account_id", "currency");
    }

    @Test
    void transactionRejectsUpdateAndDelete() {
        UUID id = UUID.ofEpochMillis(System.currentTimeMillis());
        jdbc.sql("""
                INSERT INTO journal.transaction (id, idempotency_key, occurred_at, entries, versions)
                VALUES (?, ?, now(), '[]', '[]')
                """).params(id, "schema-test-" + id).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal.transaction SET description = 'x' WHERE id = ?")
                .param(id).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal.transaction WHERE id = ?").param(id).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.sql("SELECT count(*) FROM journal.transaction WHERE id = ?").param(id)
                .query(Long.class).single()).isEqualTo(1);
    }

    private List<Map.Entry<String, String>> columns(String table) {
        return jdbc.sql("""
                SELECT column_name, data_type || ':' || is_nullable AS spec
                FROM information_schema.columns
                WHERE table_schema = 'journal' AND table_name = ?
                ORDER BY ordinal_position
                """).param(table)
                .query((rs, i) -> Map.entry(rs.getString("column_name"), rs.getString("spec")))
                .list();
    }

    private List<String> constraintColumns(String table, String type) {
        return jdbc.sql("""
                SELECT k.column_name
                FROM information_schema.table_constraints c
                JOIN information_schema.key_column_usage k
                  ON k.constraint_schema = c.constraint_schema AND k.constraint_name = c.constraint_name
                WHERE c.table_schema = 'journal' AND c.table_name = ? AND c.constraint_type = ?
                ORDER BY k.ordinal_position
                """).params(table, type)
                .query(String.class).list();
    }
}
