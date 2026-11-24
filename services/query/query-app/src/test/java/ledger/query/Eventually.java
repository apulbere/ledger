package ledger.query;

import java.time.Duration;
import java.time.Instant;

/** Polls an assertion: ingestion is asynchronous. */
public final class Eventually {

    public interface Assertion {
        void run() throws Exception;
    }

    private Eventually() {
    }

    public static void eventually(Assertion assertion) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError | Exception e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e instanceof AssertionError a ? a : new AssertionError(e);
                }
            }
            sleep(100);
        }
    }

    /** The assertion holds throughout the period, e.g. something never happens. */
    public static void throughout(Duration period, Assertion assertion) {
        Instant end = Instant.now().plus(period);
        while (Instant.now().isBefore(end)) {
            try {
                assertion.run();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            sleep(200);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
