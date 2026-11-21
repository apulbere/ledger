package ledger.journal.posting;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PostingController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final PostingRepository repository;

    PostingController(PostingRepository repository) {
        this.repository = repository;
    }

    @PostMapping(path = "/transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PostingResponse post(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey, @RequestBody PostingRequest request) {
        checkIdempotencyKey(idempotencyKey);
        ValidatedPosting posting = PostingValidator.validate(request);
        RecordedPosting recorded = repository.record(idempotencyKey, posting);
        return new PostingResponse(recorded.id(), posting.referenceId(), recorded.occurredAt(), recorded.postedAt(),
                posting.description(), posting.metadata(), posting.entries(), recorded.versions());
    }

    private static void checkIdempotencyKey(String key) {
        if (key.isEmpty()) {
            throw PostingProblem.missingIdempotencyKey();
        }
        boolean printableAscii = key.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH || !printableAscii) {
            throw PostingProblem.malformed(
                    IDEMPOTENCY_KEY + " must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " printable ASCII characters");
        }
    }
}
