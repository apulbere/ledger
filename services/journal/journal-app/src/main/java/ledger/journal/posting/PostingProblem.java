package ledger.journal.posting;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A rejected request, rendered as an RFC 9457 problem with a machine-readable code. */
public class PostingProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Integer line;
    private final Map<String, Object> properties;

    PostingProblem(HttpStatus status, String code, String detail, Integer line, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.line = line;
        this.properties = properties;
    }

    static PostingProblem malformed(String detail) {
        return new PostingProblem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", detail, null, Map.of());
    }

    static PostingProblem missingIdempotencyKey() {
        return new PostingProblem(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY",
                "The Idempotency-Key header is required", null, Map.of());
    }

    static PostingProblem idempotencyKeyReused() {
        return new PostingProblem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "A posting with this Idempotency-Key is already recorded", null, Map.of());
    }

    static PostingProblem invalid(String code, String detail) {
        return invalid(code, detail, null, Map.of());
    }

    static PostingProblem invalid(String code, String detail, Integer line) {
        return invalid(code, detail, line, Map.of());
    }

    static PostingProblem invalid(String code, String detail, Integer line, Map<String, Object> properties) {
        return new PostingProblem(HttpStatus.UNPROCESSABLE_CONTENT, code, detail, line, properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer line() {
        return line;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
