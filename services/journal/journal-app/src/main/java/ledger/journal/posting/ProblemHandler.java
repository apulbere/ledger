package ledger.journal.posting;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/** Renders errors as RFC 9457 problem details carrying a machine-readable {@code code}. */
@RestControllerAdvice
class ProblemHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PostingProblem.class)
    ResponseEntity<ProblemDetail> handlePostingProblem(PostingProblem problem) {
        return ResponseEntity.status(problem.status()).body(toProblemDetail(problem));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getMostSpecificCause() instanceof JacksonException jackson
                ? jackson.getOriginalMessage()
                : "Request body is not valid JSON";
        return ResponseEntity.badRequest().body(toProblemDetail(PostingProblem.malformed(detail)));
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing
                && PostingController.IDEMPOTENCY_KEY.equalsIgnoreCase(missing.getHeaderName())) {
            return ResponseEntity.badRequest().body(toProblemDetail(PostingProblem.missingIdempotencyKey()));
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    private static ProblemDetail toProblemDetail(PostingProblem problem) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(problem.status(), problem.getMessage());
        detail.setTitle(problem.status().getReasonPhrase());
        detail.setProperty("code", problem.code());
        if (problem.line() != null) {
            detail.setProperty("line", problem.line());
        }
        problem.properties().forEach(detail::setProperty);
        return detail;
    }
}
