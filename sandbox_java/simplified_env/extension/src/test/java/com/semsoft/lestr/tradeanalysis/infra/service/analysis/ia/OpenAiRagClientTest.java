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
        assertEquals("{\"candidates\":[]}", client.generate("instructions", "horses", schema));
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
}
