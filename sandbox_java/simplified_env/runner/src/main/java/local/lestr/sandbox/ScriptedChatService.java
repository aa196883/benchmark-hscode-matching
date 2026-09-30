package local.lestr.sandbox;

import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.ChatService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import java.util.*;

/** Deterministic test transport. The supplied model is never called. */
public final class ScriptedChatService extends ChatService {
    public record Request(String modelName, String systemMessage, String userMessage, ResponseFormat responseFormat) {}
    private final String searchResponse;
    private final String analysisResponse;
    private final List<Request> requests = new ArrayList<>();
    public ScriptedChatService(String searchResponse, String analysisResponse) {
        this.searchResponse = searchResponse;
        this.analysisResponse = analysisResponse;
    }
    @Override public String doChat(ChatModel model, String modelName, String systemMessage,
                                   String userMessage, ResponseFormat responseFormat) {
        requests.add(new Request(modelName, systemMessage, userMessage, responseFormat));
        return responseFormat == null ? analysisResponse : searchResponse;
    }
    public List<Request> requests() { return List.copyOf(requests); }
}
