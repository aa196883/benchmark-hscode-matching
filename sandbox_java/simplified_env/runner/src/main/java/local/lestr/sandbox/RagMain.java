package local.lestr.sandbox;

import com.fasterxml.jackson.databind.*;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.CompletionHSCodeChatServiceImpl;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag.*;
import java.nio.file.*;
import java.util.Map;

/** Local wiring only: all data paths are explicit and resolved against the caller's working directory. */
final class RagMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    static int run(String[] args) throws Exception {
        boolean replay = args[0].equals("rag-replay");
        int descriptionPosition = replay ? 5 : 3;
        if (args.length < descriptionPosition + 1 || args.length > descriptionPosition + 3)
            throw new IllegalArgumentException("Use --help for RAG arguments");
        int topK = args.length > descriptionPosition + 1 ? Integer.parseInt(args[descriptionPosition + 1]) : 5;
        int retrievalK = args.length > descriptionPosition + 2 ? Integer.parseInt(args[descriptionPosition + 2]) : 20;
        var catalog = new RagCatalog(Path.of(args[1]));
        var index = new PrecomputedEmbeddingIndex(Path.of(args[2]), catalog);
        RagEmbeddingClient embeddings;
        RagGenerationClient generation;
        HSCodeAnalysisService analysisDelegate;
        try (var stream = Files.newInputStream(Path.of(args[1]))) {
            var localCatalog = CatalogLoader.load(stream, HSVersion.V_2022);
            if (replay) {
                double[] vector = JSON.readValue(Files.readString(Path.of(args[3])), double[].class);
                JsonNode response = JSON.readTree(Files.readString(Path.of(args[4])));
                embeddings = new RagEmbeddingClient() {
                    @Override public Integer dimensions() { return index.configuredDimensions(); }
                    @Override public Response embed(String description) { return new Response(vector.clone(), model(), JSON.createObjectNode()); }
                };
                generation = (instructions, input, schema) -> response.deepCopy();
                analysisDelegate = LocalCompletionFactory.scripted(localCatalog, HSVersion.V_2022,
                        new ScriptedChatService(null, "Replay fixture; analysis is not evaluated"));
            } else {
                String key = System.getenv("OPENAI_API_KEY");
                if (key == null || key.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY required for rag");
                var client = new OpenAiRagClient(key, index.configuredDimensions(), OpenAiRagClient.Config.defaults());
                embeddings = client; generation = client;
                analysisDelegate = CompletionHSCodeChatServiceImpl.construct(new OpenAIProperties(key), localCatalog, HSVersion.V_2022);
            }
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
