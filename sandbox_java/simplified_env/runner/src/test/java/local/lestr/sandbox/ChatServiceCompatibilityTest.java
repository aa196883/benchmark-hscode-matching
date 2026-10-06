package local.lestr.sandbox;

import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.ChatService;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidates;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ChatServiceCompatibilityTest {
    @Test void realChatServiceBuildsMessagesAndSchemaWithoutNetwork() {
        var captured = new AtomicReference<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("fixture answer")).build();
            }
        };
        var chat = new ChatService();
        var format = chat.getResponseFormat(Candidates.class);
        assertEquals("fixture answer", chat.doChat(model, "test-model", "system", "user", format));
        assertEquals("test-model", captured.get().modelName());
        assertEquals(format, captured.get().responseFormat());
        assertEquals(2, captured.get().messages().size());
        assertEquals(SystemMessage.from("system"), captured.get().messages().get(0));
        assertEquals(UserMessage.from("user"), captured.get().messages().get(1));
        chat.doChat(model, "test-model", null, "user", null);
        assertEquals(1, captured.get().messages().size());
        assertNull(captured.get().responseFormat());
    }
    @Test void transportFailuresPropagate() {
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new IllegalStateException("transport failed"); }
        };
        assertThrows(IllegalStateException.class, () -> new ChatService().doChat(model, "test-model", null, "user", null));
    }
}
