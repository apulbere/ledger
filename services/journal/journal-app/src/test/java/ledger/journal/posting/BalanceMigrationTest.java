package ledger.journal.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.UpdateCountCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The debit/credit split migration, run against balances written by the previous schema. */
@Testcontainers
class BalanceMigrationTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    /** Changesets before the split: schema, transaction, balance, append-only trigger. */
    private static final int BEFORE_SPLIT = 4;

    private static final String CUS_A = "cus_01h455vb4pex5vsknk084sn02q";
    private static final String CUS_B = "cus_01h455vb4pex5vsknk084sn02r";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Test
    void totalsAreRebuiltFromTheLog() throws Exception {
        String url = freshDatabase();
        migrate(url, BEFORE_SPLIT);
        SingleConnectionDataSource ds = dataSource(url);
        try {
            JdbcClient jdbc = JdbcClient.create(ds);
            recordPreviousVersionPostings(jdbc);

            migrate(url, null);

            assertThat(totals(jdbc)).containsExactlyInAnyOrder(
                    // deposit 10000, hold 3000, release 3000, overdraw 12000: available -2000, held 0
                    CUS_A + "/EUR 15000/13000/3000/3000 v4",
                    CUS_B + "/USD 0/500/0/0 v1");
            assertThat(columns(jdbc)).doesNotContain("available", "held");
        } finally {
            ds.destroy();
        }
    }

    @Test
    void migrationFailsWhenTheLogDisagreesWithAStoredNet() throws Exception {
        String url = freshDatabase();
        migrate(url, BEFORE_SPLIT);
        SingleConnectionDataSource ds = dataSource(url);
        try {
            JdbcClient jdbc = JdbcClient.create(ds);
            recordPreviousVersionPostings(jdbc);
            jdbc.sql("UPDATE journal.balance SET available = available + 1 WHERE account_id = ?").param(CUS_A).update();

            assertThatThrownBy(() -> migrate(url, null)).hasStackTraceContaining("disagree with the posting log");

            assertThat(columns(jdbc)).contains("available", "held")
                    .doesNotContain("available_debit", "available_credit", "held_debit", "held_credit");
        } finally {
            ds.destroy();
        }
    }

    /** Postings and the balance rows the previous version wrote for them: net available and held. */
    private static void recordPreviousVersionPostings(JdbcClient jdbc) {
        append(jdbc, entry(CUS_A, "AVAILABLE", "CREDIT", 10000, "EUR"), internal("DEBIT", 10000, "EUR"));
        append(jdbc, entry(CUS_A, "AVAILABLE", "DEBIT", 3000, "EUR"), entry(CUS_A, "HELD", "CREDIT", 3000, "EUR"));
        append(jdbc, entry(CUS_A, "HELD", "DEBIT", 3000, "EUR"), entry(CUS_A, "AVAILABLE", "CREDIT", 3000, "EUR"));
        UUID t4 = append(jdbc, entry(CUS_A, "AVAILABLE", "DEBIT", 12000, "EUR"), internal("CREDIT", 12000, "EUR"));
        UUID t5 = append(jdbc, entry(CUS_B, "AVAILABLE", "CREDIT", 500, "USD"), internal("DEBIT", 500, "USD"));
        jdbc.sql("""
                INSERT INTO journal.balance (account_id, currency, available, held, version, last_transaction_id)
                VALUES (?, 'EUR', -2000, 0, 4, ?), (?, 'USD', 500, 0, 1, ?)
                """).params(CUS_A, t4, CUS_B, t5).update();
    }

    private static UUID append(JdbcClient jdbc, String... entries) {
        UUID id = UUID.ofEpochMillis(System.currentTimeMillis());
        jdbc.sql("""
                INSERT INTO journal.transaction (id, idempotency_key, occurred_at, entries, versions)
                VALUES (?, ?, now(), ?::jsonb, '[]')
                """).params(id, id.toString(), "[" + String.join(",", entries) + "]").update();
        return id;
    }

    private static String entry(String accountId, String bucket, String direction, long amount, String currency) {
        return """
                {"accountId": "%s", "accountType": "CUSTOMER", "bucket": "%s", "direction": "%s", "amount": %d, "currency": "%s"}"""
                .formatted(accountId, bucket, direction, amount, currency);
    }

    private static String internal(String direction, long amount, String currency) {
        return """
                {"accountId": "int_BANK_NOSTRO_%s", "accountType": "INTERNAL", "direction": "%s", "amount": %d, "currency": "%s"}"""
                .formatted(currency, direction, amount, currency);
    }

    private static List<String> totals(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT account_id || '/' || currency || ' ' || available_debit || '/' || available_credit || '/'
                       || held_debit || '/' || held_credit || ' v' || version
                FROM journal.balance
                """).query(String.class).list();
    }

    private static List<String> columns(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'journal' AND table_name = 'balance'
                """).query(String.class).list();
    }

    /** Runs the changelog up to {@code count} changesets, or all of it when {@code count} is null. */
    private static void migrate(String url, Integer count) throws Exception {
        Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor()), () -> {
            CommandScope update = count == null
                    ? new CommandScope(UpdateCommandStep.COMMAND_NAME)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                    : new CommandScope(UpdateCountCommandStep.COMMAND_NAME)
                            .addArgumentValue(UpdateCountCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                            .addArgumentValue(UpdateCountCommandStep.COUNT_ARG, count);
            update.addArgumentValue(DbUrlConnectionArgumentsCommandStep.URL_ARG, url)
                    .addArgumentValue(DbUrlConnectionArgumentsCommandStep.USERNAME_ARG, POSTGRES.getUsername())
                    .addArgumentValue(DbUrlConnectionArgumentsCommandStep.PASSWORD_ARG, POSTGRES.getPassword())
                    .execute();
        });
    }

    /** Each test gets its own database, so both can migrate from scratch. */
    private static String freshDatabase() throws SQLException {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    }

    private static SingleConnectionDataSource dataSource(String url) {
        return new SingleConnectionDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    }
}
