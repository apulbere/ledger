package ledger.journal.posting;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every request example in the OpenAPI document must be a posting the journal records, so examples can't go stale. */
class OpenApiExamplesTest extends PostingApiSupport {

    static Stream<Arguments> requestExamples() {
        Map<String, Example> examples = new OpenAPIV3Parser().read(OpenApiContract.DOCUMENT.toUri().toString())
                .getPaths().get("/transactions").getPost()
                .getRequestBody().getContent().get("application/json").getExamples();
        assertThat(examples).isNotEmpty();
        return examples.entrySet().stream().map(e -> Arguments.of(e.getKey(), Json.pretty(e.getValue().getValue())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestExamples")
    void exampleIsRecorded(String name, String body) {
        Response response = post(newKey(), body);

        assertThat(response.status()).as("%s: %s", name, response.body()).isEqualTo(201);
    }
}
