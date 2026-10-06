package local.lestr.sandbox;

import com.fasterxml.jackson.databind.*;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.CompletionHSCodeChatServiceImpl;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.*;
import java.nio.file.*;
import java.util.Map;

/** Local wiring for packaged resources and explicit replay fixtures. */
final class RagMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    static int run(String[] args) throws Exception {
        boolean external = args[0].equals("rag-replay");
        boolean replay = external || args[0].equals("rag-replay-classpath");
        int descriptionPosition = external ? 5 : replay ? 3 : 1;
        if (args.length < descriptionPosition + 1 || args.length > descriptionPosition + 3)
            throw new IllegalArgumentException("Use --help for RAG arguments");
        int topK = args.length > descriptionPosition + 1 ? Integer.parseInt(args[descriptionPosition + 1]) : 5;
        int retrievalK = args.length > descriptionPosition + 2 ? Integer.parseInt(args[descriptionPosition + 2]) : 20;
        var index = external
                ? new PrecomputedEmbeddingIndex(Path.of(args[2]), new RagCatalog(Path.of(args[1])))
                : RagHSCodeAnalysisService.loadIndex();
        RagEmbeddingClient embeddings;
        RagGenerationClient generation;
        HSCodeAnalysisService analysisDelegate;
        if (replay) {
            int vectorPosition = external ? 3 : 1;
            double[] vector = JSON.readValue(Files.readString(Path.of(args[vectorPosition])), double[].class);
            String response = Files.readString(Path.of(args[vectorPosition + 1]));
            embeddings = new RagEmbeddingClient() {
                @Override public Integer dimensions() { return index.configuredDimensions(); }
                @Override public double[] embed(String description) { return vector.clone(); }
            };
            generation = (instructions, input, schema) -> response;
            analysisDelegate = new HSCodeAnalysisService() {
                public SearchResult searchFromDescription(String description) {
                    throw new UnsupportedOperationException("Replay does not use an analysis delegate");
                }
                public AnalyseResult analyse(String description, HSCode code) {
                    throw new UnsupportedOperationException("Replay does not evaluate analysis");
                }
                public Source getSource() {
                    return Source.OpenAI_Hybrid;
                }
            };
        } else {
            String key = System.getenv("OPENAI_API_KEY");
            if (key == null || key.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY required for rag");
            var client = new OpenAiRagClient(key, index.configuredDimensions(), OpenAiRagClient.Config.defaults());
            embeddings = client; generation = client;
            // The delegated completion only uses this catalogue to validate generated codes.
            var localCatalog = new InMemoryHSCodeService(HSVersion.V_2022, index.catalog().candidates().stream()
                    .map(row -> new HSCodeWithDescription(
                            HSCode.hsCode(row.code()), row.description())).toList());
            analysisDelegate = CompletionHSCodeChatServiceImpl.construct(new OpenAIProperties(key), localCatalog, HSVersion.V_2022);
        }
        var service = new RagHSCodeAnalysisService(index, embeddings, generation, analysisDelegate, retrievalK);
        var prediction = service.searchDetailed(args[descriptionPosition], topK);
        var output = JSON.createObjectNode();
        output.set("prediction", JSON.valueToTree(prediction));
        output.put("replay", replay);
        if (prediction.status().equals("error")) output.putNull("search_result");
        else {
            var adapted = service.toSearchResult(prediction);
            output.set("search_result", JSON.valueToTree(Map.of("source", adapted.source().name(), "matchingHSCodes",
                    adapted.matchingHSCodes().stream().map(hit -> Map.of("code", hit.HSCode().toDigits(), "score", hit.score().score())).toList())));
        }
        System.out.println(JSON.writeValueAsString(output));
        return prediction.status().equals("error") ? 1 : 0;
    }
}
