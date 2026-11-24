package ledger.query.search;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.file.Path;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultHandler;

/**
 * Checks real traffic against the hand-written OpenAPI document, the source of truth for the REST contract.
 * <ul>
 * <li>every response must match a status and body the document declares;</li>
 * <li>an accepted (2xx) request must be valid per the document;</li>
 * <li>{@link #rejectedBySchema()}: a 400 the document itself can express (pattern, format, range) must be invalid
 * per the document too, so it is at least as strict as the API. A tampered cursor or an empty time range is a 400
 * the schema cannot express, so plain {@link #conforms()} does not require it.</li>
 * </ul>
 */
final class OpenApiContract {

    /** Relative to the Gradle project directory, the test working directory. Declared as a test task input. */
    static final Path DOCUMENT = Path.of("src/main/openapi/openapi.yaml");

    private static final OpenApiInteractionValidator VALIDATOR =
            OpenApiInteractionValidator.createForSpecificationUrl(DOCUMENT.toUri().toString())
                    .withBasePathOverride("/")
                    .build();

    private OpenApiContract() {
    }

    static ResultHandler conforms() {
        return result -> check(result.getRequest(), result.getResponse(), false);
    }

    static ResultHandler rejectedBySchema() {
        return result -> check(result.getRequest(), result.getResponse(), true);
    }

    private static void check(MockHttpServletRequest servletRequest, MockHttpServletResponse servletResponse,
            boolean schemaRejects) throws Exception {
        SimpleRequest.Builder requestBuilder = new SimpleRequest.Builder(
                servletRequest.getMethod(), servletRequest.getRequestURI());
        servletRequest.getParameterMap().forEach(requestBuilder::withQueryParam);
        Request request = requestBuilder.build();

        int status = servletResponse.getStatus();
        String body = servletResponse.getContentAsString();
        SimpleResponse.Builder responseBuilder = SimpleResponse.Builder.status(status).withBody(body);
        if (servletResponse.getContentType() != null) {
            responseBuilder.withContentType(servletResponse.getContentType());
        }

        ValidationReport responseReport =
                VALIDATOR.validateResponse(request.getPath(), request.getMethod(), responseBuilder.build());
        if (responseReport.hasErrors()) {
            throw new AssertionError("Response " + status + " does not conform to the OpenAPI document:\n"
                    + responseReport + "\nbody: " + body);
        }
        ValidationReport requestReport = VALIDATOR.validateRequest(request);
        if (status >= 200 && status < 300 && requestReport.hasErrors()) {
            throw new AssertionError("Accepted request does not conform to the OpenAPI document:\n" + requestReport);
        }
        if (schemaRejects && !requestReport.hasErrors()) {
            throw new AssertionError("Request rejected with " + status + " is valid per the OpenAPI document, "
                    + "so the document is looser than the API: " + servletRequest.getRequestURI() + "?"
                    + servletRequest.getQueryString());
        }
    }
}
