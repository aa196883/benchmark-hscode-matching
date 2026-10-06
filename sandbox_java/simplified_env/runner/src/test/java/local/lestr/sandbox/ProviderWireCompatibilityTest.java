package local.lestr.sandbox;

import com.fasterxml.jackson.databind.*;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.*;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidates;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Real LangChain4j serialization against a loopback HTTP fixture, never OpenAI. */
class ProviderWireCompatibilityTest {
    @Test void pinnedDependenciesSupportChatAndEmbeddingWireFormats() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                String response = exchange.getRequestURI().getPath().endsWith("embeddings")
                        ? """
                          {"object":"list","model":"text-embedding-3-small","data":[{"object":"embedding","index":0,"embedding":[1.0,0.0]}],"usage":{"prompt_tokens":2,"total_tokens":2}}
                          """
                        : """
                          {"id":"fixture","object":"chat.completion","created":0,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"fixture answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":2,"total_tokens":4}}
                          """;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
            var model = OpenAiChatModel.builder().apiKey("fixture-key").modelName("test-model")
                    .baseUrl(baseUrl).timeout(Duration.ofSeconds(5)).maxRetries(0).build();
            var chat = new ChatService();
            assertEquals("fixture answer", chat.doChat(model, "test-model", "system", "user", chat.getResponseFormat(Candidates.class)));
            assertEquals("system", requests.getFirst().path("messages").get(0).path("role").asText());
            assertEquals("user", requests.getFirst().path("messages").get(1).path("content").asText());
            assertFalse(requests.getFirst().path("response_format").isMissingNode());
            var embeddingModel = OpenAiEmbeddingModel.builder().apiKey("fixture-key").modelName("text-embedding-3-small")
                    .baseUrl(baseUrl).timeout(Duration.ofSeconds(5)).maxRetries(0).build();
            var catalog = new InMemoryHSCodeService(HSVersion.V_2022,
                    List.of(new HSCodeWithDescription(HSCode.hsCode("010121"), "Breeding horses")));
            var store = new EmbeddingService().indexStore(catalog, embeddingModel);
            var hits = store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1,0})).maxResults(1).build()).matches();
            assertEquals("0101.21", hits.getFirst().embedded().metadata().getString("hsCode"));
            assertEquals("Breeding horses", hits.getFirst().embedded().text());
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).path("input").toString().contains("Breeding horses"));
        } finally { server.stop(0); }
    }
}
