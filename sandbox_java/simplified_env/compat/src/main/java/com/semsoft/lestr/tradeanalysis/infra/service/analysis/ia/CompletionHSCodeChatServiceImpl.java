package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.AnalyseResult;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import com.semsoft.lestr.tradeanalysis.domain.model.SearchResult;
import com.semsoft.lestr.tradeanalysis.domain.model.Source;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.MatchingHSCodeComparator;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidates;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class CompletionHSCodeChatServiceImpl implements HSCodeAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(CompletionHSCodeChatServiceImpl.class);

    private final boolean hsCodeConversion = true;

    private final String searchDeveloperMessage;
    private final String analyseDeveloperMessage;

    private final String analyseUserMessage;

    private final String modelName;
    private final HSVersion hsVersion;
    private final HSCodeService hsCodeService;
    private final OpenAiChatModel chatModel;
    private final ChatService chatService;
    private final ResponseFormatUtils responseFormatUtils = new ResponseFormatUtils();


    public CompletionHSCodeChatServiceImpl(String searchDeveloperMessage, String analyseDeveloperMessage, String analyseUserMessage, String modelName, HSVersion hsVersion, HSCodeService hsCodeService, OpenAiChatModel chatModel, ChatService chatService) {
        this.searchDeveloperMessage = searchDeveloperMessage;
        this.analyseDeveloperMessage = analyseDeveloperMessage;
        this.analyseUserMessage = analyseUserMessage;
        this.modelName = modelName;
        this.hsVersion = hsVersion;
        this.hsCodeService = hsCodeService;
        this.chatModel = chatModel;
        this.chatService = chatService;
    }

    public static CompletionHSCodeChatServiceImpl construct(OpenAIProperties openAIProperties, HSCodeService hsCodeService, HSVersion hsVersion) {
        String searchDeveloperMessage = """
                You are an expert in the Harmonized System (HS) $year. You will be provided with a merchandise description, and your task is to return all relevant 6-digit HS $year code candidates matching this description along with a percent relevance score in JSON format.
                
                If the merchandise description is not specific enough to determine a 6-digit code, limit your response to the most relevant 2 or 4-digit HS codes (max $nbResponse responses). Ensure that you provide codes that cover the general category of the described merchandise.
                """;

        return CompletionHSCodeChatServiceImpl.construct(searchDeveloperMessage, openAIProperties, hsCodeService, hsVersion);
    }

    public static CompletionHSCodeChatServiceImpl construct(String searchDeveloperMessage, OpenAIProperties openAIProperties, HSCodeService hsCodeService, HSVersion hsVersion) {
        String analyseDeveloperMessage = """
                You are a HS $year expert.
                You will be provided with a merchandise description and a HS $year code,
                and your task is to explain in a concise way how well the description fit the HS $year code.
                Each conversation will be single-turn.
                """.replace("$year", hsVersion.toString());
        String analyseUserMessage = "How well would the description \"%s\" fit HS " + hsVersion + " code %s?";
        com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.ChatService chatService = new ChatService();

        return new CompletionHSCodeChatServiceImpl(
                searchDeveloperMessage
                        .replace("$year", hsVersion.toString())
                        .replace("$nbResponse", Integer.toString(HSCodeAnalysisService.NB_MAX_RESULTS)),
                analyseDeveloperMessage,
                analyseUserMessage,
                openAIProperties.modelName(), hsVersion, hsCodeService, chatService.buildChatModel(openAIProperties), chatService);
    }

    @Override
    public SearchResult searchFromDescription(String description) {
        log.debug("Chat prompt '{}' with '{}' on model {}", searchDeveloperMessage, description, modelName);
        String response = chatService.doChat(chatModel, modelName, searchDeveloperMessage, description, chatService.getResponseFormat(Candidates.class));

        if (response == null) {
            return new SearchResult(getSource(), List.of());
        }
        Optional<Candidates> candidates = responseFormatUtils.parseResponseCandidates(response);
        log.info("Candidates for {} : {}", description, candidates);
        return candidates
                .map(value -> new SearchResult(getSource(),
                                value.candidates().stream()
                                        .flatMap(c -> responseFormatUtils.convert(c, hsCodeService, hsVersion, hsCodeConversion).stream())
                                        .sorted(new MatchingHSCodeComparator())
                                        .toList()
                        )
                )
                .orElseGet(() -> new SearchResult(getSource(), List.of()));
    }

    @Override
    public AnalyseResult analyse(String description, HSCode hsCode) {
        return new AnalyseResult(getSource(), chatService.doChat(chatModel, modelName, analyseDeveloperMessage,
                String.format(analyseUserMessage, description, hsCode.toString()), null));
    }

    @Override
    public Source getSource() {
        return Source.OpenAI_ChatGPT;
    }
}
