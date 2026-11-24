package ledger.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void transactionTable() {
        assertThat(columns("transaction")).containsExactly(
                Map.entry("id", "uuid:NO"),
                Map.entry("reference_id", "text:YES"),
                Map.entry("occurred_at", "timestamp with time zone:NO"),
                Map.entry("posted_at", "timestamp with time zone:NO"),
                Map.entry("description", "text:YES"),
                Map.entry("metadata", "jsonb:YES"),
                Map.entry("entries", "jsonb:NO"),
                Map.entry("versions", "jsonb:NO"));
        assertThat(constraintColumns("transaction", "PRIMARY KEY")).containsExactly("id");
    }

    @Test
    void accountTransactionTable() {
        assertThat(columns("account_transaction")).containsExactly(
                Map.entry("account_id", "text:NO"),
                Map.entry("transaction_id", "uuid:NO"),
                Map.entry("occurred_at", "timestamp with time zone:NO"),
                Map.entry("currencies", "ARRAY:NO"));
        assertThat(constraintColumns("account_transaction", "PRIMARY KEY"))
                .containsExactly("account_id", "transaction_id");
        assertThat(constraintColumns("account_transaction", "FOREIGN KEY")).containsExactly("transaction_id");
        assertThat(indexDefinition("account_transaction_search_idx"))
                .contains("(account_id, occurred_at DESC, transaction_id DESC)");
    }

    @Test
    void balanceVersionTable() {
        assertThat(columns("balance_version")).containsExactly(
                Map.entry("account_id", "text:NO"),
                Map.entry("currency", "character:NO"),
                Map.entry("version", "bigint:NO"),
                Map.entry("available_debit", "bigint:NO"),
                Map.entry("available_credit", "bigint:NO"),
                Map.entry("held_debit", "bigint:NO"),
                Map.entry("held_credit", "bigint:NO"),
                Map.entry("last_transaction_id", "uuid:NO"));
        assertThat(constraintColumns("balance_version", "PRIMARY KEY"))
                .containsExactly("account_id", "currency", "version");
        assertThat(indexDefinition("balance_version_last_transaction_idx")).contains("(last_transaction_id)");
    }

    private List<Map.Entry<String, String>> columns(String table) {
        return jdbc.sql("""
                SELECT column_name, data_type || ':' || is_nullable AS spec
                FROM information_schema.columns
                WHERE table_schema = 'query' AND table_name = ?
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
                WHERE c.table_schema = 'query' AND c.table_name = ? AND c.constraint_type = ?
                ORDER BY k.ordinal_position
                """).params(table, type)
                .query(String.class).list();
    }

    private String indexDefinition(String index) {
        return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'query' AND indexname = ?")
                .param(index).query(String.class).single();
    }
}
