package ledger.journal.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Swagger UI renders the hand-written contract. */
class SwaggerUiTest extends PostingApiSupport {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void swaggerUiIsServed() throws Exception {
        HttpResponse<String> response = get("/swagger-ui/index.html");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("swagger-ui");
    }

    @Test
    void swaggerUiPointsAtTheHandWrittenDocument() throws Exception {
        assertThat(get("/v3/api-docs/swagger-config").body()).contains("\"url\":\"/openapi.yaml\"");
    }

    @Test
    void handWrittenDocumentIsServedUnchanged() throws Exception {
        HttpResponse<String> response = get("/openapi.yaml");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(Files.readString(OpenApiContract.DOCUMENT));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
