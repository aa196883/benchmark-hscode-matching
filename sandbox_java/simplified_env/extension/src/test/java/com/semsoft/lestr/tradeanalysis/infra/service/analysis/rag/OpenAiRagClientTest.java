package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag.RagJson.*;

class OpenAiRagClientTest {
    HttpServer server;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    volatile String response = "{}";
    volatile int status = 200;
    volatile int delayMs = 0;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0),0);
        server.createContext("/v1/", exchange -> {
            try {
                requests.add(MAPPER.readTree(exchange.getRequestBody())); paths.add(exchange.getRequestURI().getPath());
                if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("x-request-id", "fixture-request");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiRagClient client(Integer dimensions, OpenAiRagClient.Config config) {
        return new OpenAiRagClient("secret-fixture", dimensions, config, HttpClient.newHttpClient(), URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/"));
    }
    @Test void responsePayloadMatchesPythonAndPreservesEnvelope() {
        response = """
                {"status":"completed","model":"test","output":[{"type":"message","content":[{"type":"refusal","refusal":"No"}]}]}
                """;
        var client = client(null, OpenAiRagClient.Config.defaults());
        var result = client.generate("instructions", "input", parse("{\"type\":\"object\"}"));
        assertEquals(parse(response), result);
        assertEquals(List.of("/v1/responses"), paths);
        var request = requests.getFirst();
        assertEquals("gpt-4.1-mini", request.get("model").asText());
        assertEquals(2048, request.get("max_output_tokens").asInt());
        assertFalse(request.get("store").asBoolean());
        assertEquals("json_schema", request.path("text").path("format").path("type").asText());
        assertTrue(request.path("text").path("format").path("strict").asBoolean());
        assertFalse(request.has("temperature")); assertFalse(request.has("reasoning"));
        assertFalse(client.configuration().toString().contains("secret-fixture"));
    }
    @Test void embeddingPayloadUsesSmallModelAndOptionalDimensions() {
        response = """
                {"model":"text-embedding-3-small","data":[{"index":0,"embedding":[1,0]}],"usage":{"total_tokens":2}}
                """;
        var client = client(null, OpenAiRagClient.Config.defaults());
        assertArrayEquals(new double[]{1,0}, client.embed("horses").vector());
        var request = requests.getFirst();
        assertEquals("/v1/embeddings", paths.getFirst());
        assertEquals("text-embedding-3-small", request.get("model").asText());
        assertEquals(parse("[\"horses\"]"), request.get("input"));
        assertEquals("float", request.get("encoding_format").asText());
        assertFalse(request.has("dimensions"));
        client(2, OpenAiRagClient.Config.defaults()).embed("horses");
        assertEquals(2, requests.getLast().get("dimensions").asInt());
    }
    @Test void configuredGenerationParametersAndIncompleteResponsesAreNotLost() {
        response = "{\"status\":\"incomplete\"}";
        var config = new OpenAiRagClient.Config("test-model",100,0.2,"low",Duration.ofSeconds(5));
        assertEquals("incomplete", client(null,config).generate("i","u",parse("{}")).get("status").asText());
        var request = requests.getFirst();
        assertEquals(0.2, request.get("temperature").asDouble());
        assertEquals("low", request.path("reasoning").path("effort").asText());
    }
    @Test void httpFailureIsSanitizedAndDoesNotRetry() {
        response = "secret-fixture private error payload"; status = 429;
        var error = assertThrows(RagProviderException.class, () -> client(null,OpenAiRagClient.Config.defaults()).generate("i","u",parse("{}")));
        assertEquals(429,error.details().get("status_code").asInt());
        assertEquals("fixture-request",error.details().get("request_id").asText());
        assertFalse(error.details().toString().contains("secret-fixture"));
        assertEquals(1,requests.size());
    }
    @Test void malformedResponsesAndEmbeddingIndicesAreRejected() {
        var client=client(null,OpenAiRagClient.Config.defaults());
        for (String raw : List.of("not JSON", "[]", "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":1,\"embedding\":[1,0]}]}",
                "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":0,\"embedding\":[0,0]}]}")) {
            response=raw;
            var error=assertThrows(RagProviderException.class, () -> client.embed("horses"));
            assertEquals("invalid_response",error.details().get("kind").asText());
        }
    }
    @Test void timeoutIsReportedAsConnectionError() {
        delayMs=300;
        var config=new OpenAiRagClient.Config("test",100,null,null,Duration.ofMillis(30));
        var error=assertThrows(RagProviderException.class, () -> client(null,config).generate("i","u",parse("{}")));
        assertEquals("connection_error",error.details().get("kind").asText());
    }
}
