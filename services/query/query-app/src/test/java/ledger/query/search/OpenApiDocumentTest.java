package ledger.query.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

/** The REST contract must be a valid OpenAPI document, including the parts no test traffic exercises. */
class OpenApiDocumentTest {

    @Test
    void documentParsesWithoutMessages() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
                .readLocation(OpenApiContract.DOCUMENT.toUri().toString(), null, options);

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getPaths()).containsKey("/accounts/{accountId}/transactions");
    }

    @Test
    void exampleCursorIsOneTheApiIssues() {
        assertThat(Cursor.decode("eyJvIjoiMjAyNi0xMS0yMVQxMjozMDowMFoiLCJpIjoiMDE5YWI3YzQtNWUyZi03YTMxLTljMGQtNGI4ZTJmNmExZDM3In0"))
                .isEqualTo(new Cursor(java.time.Instant.parse("2026-11-21T12:30:00Z"),
                        java.util.UUID.fromString("019ab7c4-5e2f-7a31-9c0d-4b8e2f6a1d37")));
    }
}
