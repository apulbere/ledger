package ledger.journal.posting;

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
        assertThat(result.getOpenAPI().getPaths()).containsKey("/transactions");
    }
}
