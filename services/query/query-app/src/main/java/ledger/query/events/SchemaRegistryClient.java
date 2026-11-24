package ledger.query.events;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;
import tools.jackson.databind.json.JsonMapper;

/** Writer schemas by id from Schema Registry's REST API. Ids are immutable, so each is fetched once. */
public final class SchemaRegistryClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Integer, Schema> schemas = new ConcurrentHashMap<>();

    public SchemaRegistryClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public Schema schema(int id) {
        return schemas.computeIfAbsent(id, this::fetch);
    }

    private Schema fetch(int id) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/schemas/ids/" + id))
                .timeout(Duration.ofSeconds(10))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EventDecodingException(
                        "Schema " + id + " not available: " + response.statusCode() + " " + response.body());
            }
            return new Schema.Parser().parse(JSON.readTree(response.body()).get("schema").asString());
        } catch (IOException e) {
            throw new EventDecodingException("Schema Registry unreachable for schema " + id, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventDecodingException("Interrupted fetching schema " + id, e);
        }
    }
}
