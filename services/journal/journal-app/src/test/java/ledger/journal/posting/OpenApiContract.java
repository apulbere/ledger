package ledger.journal.posting;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultHandler;

/**
 * Checks real traffic against the hand-written OpenAPI document, the source of truth for the REST contract.
 * <ul>
 * <li>every response must match a status and body the document declares;</li>
 * <li>an accepted (2xx) request must be valid per the document;</li>
 * <li>a request rejected with 400 must be invalid per the document too, so the document is at least as strict
 * as the parser. Requests rejected with 422 are not checked: some break the schema, others only a business rule.</li>
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

    /** For MockMvc: {@code mvc.perform(...).andDo(OpenApiContract.conforms())}. */
    static ResultHandler conforms() {
        return result -> {
            MockHttpServletRequest request = result.getRequest();
            MockHttpServletResponse response = result.getResponse();
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            for (String name : Collections.list(request.getHeaderNames())) {
                headers.put(name, request.getHeader(name));
            }
            check(request.getMethod(), request.getRequestURI(), headers, request.getContentAsString(),
                    response.getStatus(), response.getContentType(), response.getContentAsString());
        };
    }

    static void check(String method, String path, Map<String, String> requestHeaders, String requestBody,
            int status, String responseContentType, String responseBody) {
        SimpleRequest.Builder requestBuilder = new SimpleRequest.Builder(method, path).withBody(requestBody);
        requestHeaders.forEach(requestBuilder::withHeader);
        Request request = requestBuilder.build();

        SimpleResponse.Builder responseBuilder = SimpleResponse.Builder.status(status).withBody(responseBody);
        if (responseContentType != null) {
            responseBuilder.withContentType(responseContentType);
        }
        Response response = responseBuilder.build();

        ValidationReport responseReport = VALIDATOR.validateResponse(path, request.getMethod(), response);
        if (responseReport.hasErrors()) {
            throw new AssertionError("Response " + status + " does not conform to the OpenAPI document:\n"
                    + responseReport + "\nbody: " + responseBody);
        }
        if (status >= 200 && status < 300) {
            ValidationReport requestReport = VALIDATOR.validateRequest(request);
            if (requestReport.hasErrors()) {
                throw new AssertionError("Accepted request does not conform to the OpenAPI document:\n"
                        + requestReport + "\nbody: " + requestBody);
            }
        } else if (status == 400 && !VALIDATOR.validateRequest(request).hasErrors()) {
            throw new AssertionError("Request rejected with 400 is valid per the OpenAPI document, "
                    + "so the document is looser than the API. headers: " + requestHeaders + "\nbody: " + requestBody);
        }
    }
}
