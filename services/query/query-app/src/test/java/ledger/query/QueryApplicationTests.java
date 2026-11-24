package ledger.query;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// No topic exists here: query must start before the connector has created them.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QueryApplicationTests {

    @Test
    void contextLoads() {
    }
}
