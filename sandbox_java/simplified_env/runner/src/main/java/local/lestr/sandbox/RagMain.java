package local.lestr.sandbox;

import com.fasterxml.jackson.databind.*;
import com.semsoft.lestr.tradeanalysis.infra.service.HSCodeServiceImpl;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.*;
import java.nio.file.*;

/** Local wiring for packaged resources and explicit replay fixtures. */
final class RagMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static javax.sql.DataSource datasource(boolean importer) {
        String prefix = importer ? "RAG_IMPORT_DB_" : "RAG_DB_";
        var datasource = new org.postgresql.ds.PGSimpleDataSource();
        datasource.setUrl(environment(prefix + "URL"));
        datasource.setUser(environment(prefix + "USER"));
        datasource.setPassword(environment(prefix + "PASSWORD"));
        datasource.setConnectTimeout(10);
        datasource.setSocketTimeout(60);
        return datasource;
    }
    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
    static int run(String[] args) throws Exception {
        var hsCodeService = new HSCodeServiceImpl();
        if (args[0].equals("rag-import")) {
            if (args.length != 1) throw new IllegalArgumentException("rag-import takes no arguments");
            boolean imported = RagIndexImporter.importIndex(datasource(true), PrecomputedIndexResources.packaged(hsCodeService));
            System.out.println(imported ? "RAG index imported" : "RAG index already imported and verified");
            return 0;
        }
        boolean replay = args[0].equals("rag-replay");
        int descriptionPosition = replay ? 3 : 1;
        if (args.length < descriptionPosition + 1 || args.length > descriptionPosition + 3)
            throw new IllegalArgumentException("Use --help for RAG arguments");
        int topK = args.length > descriptionPosition + 1 ? Integer.parseInt(args[descriptionPosition + 1]) : 5;
        int retrievalK = args.length > descriptionPosition + 2 ? Integer.parseInt(args[descriptionPosition + 2]) : 20;
        var index = RagHSCodeAnalysisService.loadIndex(datasource(false), hsCodeService);
        RagEmbeddingClient embeddings;
        RagGenerationClient_v2 generation;
        if (replay) {
            int vectorPosition = 1;
            double[] vector = JSON.readValue(Files.readString(Path.of(args[vectorPosition])), double[].class);
            String response = Files.readString(Path.of(args[vectorPosition + 1]));
            embeddings = new RagEmbeddingClient() {
                @Override public Integer dimensions() { return index.configuredDimensions(); }
                @Override public double[] embed(String description) { return vector.clone(); }
            };
            generation = (instructions, input, schema) -> new RagGenerationClient_v2.Answer(response);
        } else {
            String key = System.getenv("OPENAI_API_KEY");
            if (key == null || key.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY required for rag");
            var client = new OpenAiRagClient(key, index.configuredDimensions(), OpenAiRagClient.Config.defaults());
            embeddings = client; generation = client;
        }
        var service = new RagHSCodeAnalysisService(index, embeddings, generation, retrievalK);
        var prediction = service.searchDetailed(args[descriptionPosition], topK);
        System.out.println(JSON.writeValueAsString(prediction));
        return 0;
    }
}
