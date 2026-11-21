package ledger.journal.posting;

import static ledger.journal.posting.Fixtures.credit;
import static ledger.journal.posting.Fixtures.debit;
import static ledger.journal.posting.Fixtures.posting;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PostingConcurrencyTest extends PostingApiSupport {

    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @Autowired
    DataSource dataSource;

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void parallelPostingsOnOneBalanceGetContiguousVersions() {
        String a = newCustomer();
        int n = 40;

        List<Response> responses = runConcurrently(n, i -> () ->
                post(newKey(), posting(credit(a, "AVAILABLE", 100, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 100, "EUR"))));

        assertThat(responses).extracting(Response::status).containsOnly(201);
        assertThat(recordedVersions(a, "EUR")).containsExactlyElementsOf(LongStream.rangeClosed(1, n).boxed().toList());
        Balance balance = balance(a, "EUR").orElseThrow();
        assertThat(balance.available()).isEqualTo(100L * n);
        assertThat(balance.version()).isEqualTo(n);
    }

    @Test
    void concurrentDuplicatesRecordOnce() {
        for (int round = 0; round < 10; round++) {
            String a = newCustomer();
            String key = newKey();
            PostingRequest request = posting(credit(a, "AVAILABLE", 100, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 100, "EUR"));

            List<Response> responses = runConcurrently(2, i -> () -> post(key, request));

            assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
            assertThat(postingsWithKey(key)).isEqualTo(1);
            assertThat(balance(a, "EUR").orElseThrow()).extracting(Balance::available, Balance::version)
                    .containsExactly(100L, 1L);
        }
    }

    @Test
    void oppositeOrderPostingsDoNotDeadlock() {
        String a = newCustomer();
        String b = newCustomer();
        int n = 40;

        // Half the postings list A before B, half B before A; the sorted batch locks both in the same order.
        List<Response> responses = runConcurrently(n, i -> () -> post(newKey(), i % 2 == 0
                ? posting(debit(a, "AVAILABLE", 10, "EUR"), credit(b, "AVAILABLE", 10, "EUR"))
                : posting(debit(b, "AVAILABLE", 10, "EUR"), credit(a, "AVAILABLE", 10, "EUR"))));

        assertThat(responses).extracting(Response::status).containsOnly(201);
        assertThat(balance(a, "EUR").orElseThrow()).extracting(Balance::available, Balance::version)
                .containsExactly(0L, (long) n);
        assertThat(balance(b, "EUR").orElseThrow()).extracting(Balance::available, Balance::version)
                .containsExactly(0L, (long) n);
    }

    @Test
    void postingsOnDifferentBalancesDoNotBlockEachOther() throws Exception {
        String locked = newCustomer();
        String other = newCustomer();
        record(posting(credit(locked, "AVAILABLE", 100, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 100, "EUR")));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT 1 FROM journal.balance WHERE account_id = ? FOR UPDATE")) {
                lock.setString(1, locked);
                lock.executeQuery();
            }

            CompletableFuture<Response> onLocked = CompletableFuture.supplyAsync(() ->
                    post(newKey(), posting(credit(locked, "AVAILABLE", 1, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 1, "EUR"))), pool);
            CompletableFuture<Response> onOther = CompletableFuture.supplyAsync(() ->
                    post(newKey(), posting(credit(other, "AVAILABLE", 1, "EUR"), debit("int_BANK_NOSTRO_EUR", null, 1, "EUR"))), pool);
            CompletableFuture<Response> internalOnly = CompletableFuture.supplyAsync(() ->
                    post(newKey(), posting(debit("int_SETTLEMENT_VISA", null, 1, "EUR"), credit("int_BANK_NOSTRO_EUR", null, 1, "EUR"))), pool);

            assertThat(onOther.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(internalOnly.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(isBlocked(onLocked)).as("posting on the locked balance waits").isTrue();

            connection.rollback();
            assertThat(onLocked.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
        assertThat(balance(locked, "EUR").orElseThrow().version()).isEqualTo(2);
    }

    private static boolean isBlocked(CompletableFuture<?> future) throws Exception {
        try {
            future.get(500, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        }
    }

    private interface Task {
        Supplier<Response> forIndex(int i);
    }

    /** Starts all tasks together behind a latch and waits for every response. */
    private List<Response> runConcurrently(int n, Task task) {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Response>> futures = IntStream.range(0, n)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return task.forIndex(i).get();
                }, pool))
                .toList();
        start.countDown();
        return futures.stream().map(f -> f.orTimeout(30, TimeUnit.SECONDS).join()).toList();
    }
}
