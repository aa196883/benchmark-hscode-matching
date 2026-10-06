package local.lestr.sandbox;

import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import com.semsoft.lestr.tradeanalysis.domain.spi.*;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.*;

/** Local wiring only. Prompt snapshot from CompletionHSCodeChatServiceImpl.construct. */
public final class LocalCompletionFactory {
    private LocalCompletionFactory() {}
    public static CompletionHSCodeChatServiceImpl scripted(HSCodeService hsCodeService, HSVersion hsVersion,
                                                          ScriptedChatService chatService) {
        var openAIProperties = new OpenAIProperties("offline-unused-key");
        String searchDeveloperMessage = """
                You are an expert in the Harmonized System (HS) $year. You will be provided with a merchandise description, and your task is to return all relevant 6-digit HS $year code candidates matching this description along with a percent relevance score in JSON format.
                
                If the merchandise description is not specific enough to determine a 6-digit code, limit your response to the most relevant 2 or 4-digit HS codes (max $nbResponse responses). Ensure that you provide codes that cover the general category of the described merchandise.
                """;

        String analyseDeveloperMessage = """
                You are a HS $year expert.
                You will be provided with a merchandise description and a HS $year code,
                and your task is to explain in a concise way how well the description fit the HS $year code.
                Each conversation will be single-turn.
                """.replace("$year", hsVersion.toString());
        String analyseUserMessage = "How well would the description \"%s\" fit HS " + hsVersion + " code %s?";
        return new CompletionHSCodeChatServiceImpl(
                searchDeveloperMessage
                        .replace("$year", hsVersion.toString())
                        .replace("$nbResponse", Integer.toString(HSCodeAnalysisService.NB_MAX_RESULTS)),
                analyseDeveloperMessage,
                analyseUserMessage,
                openAIProperties.modelName(), hsVersion, hsCodeService, chatService.buildChatModel(openAIProperties), chatService);
    }
}
