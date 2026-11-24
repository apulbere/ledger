package ledger.query.events;

/** A record that cannot be turned into a contract event. Retried, never skipped: see the listeners' error handler. */
public class EventDecodingException extends RuntimeException {

    public EventDecodingException(String message) {
        super(message);
    }

    public EventDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
