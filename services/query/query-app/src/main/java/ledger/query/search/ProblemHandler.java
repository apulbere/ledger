package ledger.query.search;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders errors as RFC 9457 problem details carrying a machine-readable {@code code}. */
@RestControllerAdvice
class ProblemHandler {

    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ProblemDetail> invalidParameter(InvalidParameterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        problem.setProperty("code", "INVALID_PARAMETER");
        problem.setProperty("parameter", e.parameter());
        return ResponseEntity.badRequest().body(problem);
    }
}
