package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

class OpenAiRagClientTest {
    @Test void generationSendsMessagesAndSchemaAndReturnsText() {
        var captured = new AtomicReference<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"candidates\":[]}")).build();
            }
        };
        var client = new OpenAiRagClient(model, null, null);
        var schema = parse("{\"type\":\"object\",\"properties\":{\"code\":{\"enum\":[\"010121\"]}}}");
        assertEquals("{\"candidates\":[]}", ((RagGenerationClient.Answer) client.generate("instructions", "horses", schema)).text());
        var request = captured.get();
        assertEquals(SystemMessage.from("instructions"), request.messages().get(0));
        assertEquals(UserMessage.from("horses"), request.messages().get(1));
        var format = request.parameters().responseFormat().jsonSchema();
        assertEquals("hs_prediction", format.name());
        assertEquals(schema, parse(((JsonRawSchema) format.rootElement()).schema()));
    }
    @Test void embeddingReturnsModelVector() {
        EmbeddingModel model = new EmbeddingModel() {
            @Override public Response<Embedding> embed(String text) {
                assertEquals("horses", text);
                return Response.from(Embedding.from(new float[]{0.5f, -0.25f}));
            }
        };
        var client = new OpenAiRagClient(null, model, 2);
        assertArrayEquals(new double[]{0.5, -0.25}, client.embed("horses"));
        assertEquals(2, client.dimensions());
    }
    @Test void modelExceptionsPropagateUnchanged() {
        var failure = new IllegalStateException("model unavailable");
        ChatModel chat = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { throw failure; }
        };
        EmbeddingModel embeddings = new EmbeddingModel() {
            @Override public Response<Embedding> embed(String text) { throw failure; }
        };
        var client = new OpenAiRagClient(chat, embeddings, null);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> client.generate("i", "u", parse("{}"))));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.embed("horses")));
    }

    @Test void responsesWireDistinguishesRefusalIncompleteAndEmptyOutput() throws Exception {
        var body = new AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var model = dev.langchain4j.model.openai.OpenAiResponsesChatModel.builder()
                    .apiKey("test-only").modelName("fixture")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .httpClientBuilder(dev.langchain4j.http.client.jdk.JdkHttpClient.builder()
                            .connectTimeout(java.time.Duration.ofSeconds(5)).readTimeout(java.time.Duration.ofSeconds(5))).build();
            var client = new OpenAiRagClient(model, null, null);
            body.set("""
                    {"id":"resp_test","status":"completed","model":"fixture","output":[
                      {"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"Declined"}]}]}
                    """);
            assertInstanceOf(RagGenerationClient.Refusal.class, client.generate("i", "u", parse("{}")));
            body.set(body.get().replace("completed", "incomplete"));
            assertThrows(com.semsoft.lestr.tradeanalysis.domain.model.HSCodeAnalysisException.class,
                    () -> client.generate("i", "u", parse("{}")));
            body.set("{\"id\":\"resp_test\",\"status\":\"completed\",\"model\":\"fixture\",\"output\":[]}");
            assertThrows(com.semsoft.lestr.tradeanalysis.domain.model.HSCodeAnalysisException.class,
                    () -> client.generate("i", "u", parse("{}")));
        } finally { server.stop(0); }
    }
}
