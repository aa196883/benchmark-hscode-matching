package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.*;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import java.io.*;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** RAG service using rag_v1. Generation order is preserved; fixed display scores never influence ranking. */
public final class RagHSCodeAnalysisService implements HSCodeAnalysisService {
    public static final int DEFAULT_RETRIEVAL_K = 20;
    public static final int INDUSTRIAL_SCORE = 3;
    private static final String PROMPT = resource("rag_v1.txt");
    private static final JsonNode SCHEMA = parse(resource("rag_v1.schema.json"));
    private final PrecomputedEmbeddingIndex index;
    private final RagEmbeddingClient embeddings;
    private final RagGenerationClient generation;
    private final HSCodeAnalysisService analysisDelegate;
    private final int retrievalK;

    public RagHSCodeAnalysisService(PrecomputedEmbeddingIndex index, RagEmbeddingClient embeddings,
                                   RagGenerationClient generation, HSCodeAnalysisService analysisDelegate, int retrievalK) {
        this.index = Objects.requireNonNull(index);
        this.embeddings = Objects.requireNonNull(embeddings);
        this.generation = Objects.requireNonNull(generation);
        this.analysisDelegate = Objects.requireNonNull(analysisDelegate);
        this.retrievalK = retrievalK;
        require(Objects.equals(index.configuredDimensions(), embeddings.dimensions()), "Query vectorizer config must match the index");
    }
    public static RagHSCodeAnalysisService construct(OpenAIProperties properties,
                                                     HSCodeAnalysisService analysisDelegate, DataSource datasource, HSCodeService hsCodeService) throws IOException, SQLException {
        var index = loadIndex(datasource, hsCodeService);
        var defaults = OpenAiRagClient.Config.defaults();
        var config = new OpenAiRagClient.Config(properties.modelName(), defaults.maxOutputTokens(), null, null);
        var client = new OpenAiRagClient(properties.apiKey(), index.configuredDimensions(), config);
        return new RagHSCodeAnalysisService(index, client, client, analysisDelegate, DEFAULT_RETRIEVAL_K);
    }
    /** Checks the database against HS service descriptions and packaged manifest; does not read or import vectors. */
    public static PrecomputedEmbeddingIndex loadIndex(DataSource datasource, HSCodeService hsCodeService) throws IOException, SQLException {
        return new PrecomputedEmbeddingIndex(datasource, PrecomputedIndexResources.packaged(hsCodeService));
    }
    @Override public SearchResult searchFromDescription(String description) {
        return toSearchResult(searchDetailed(description));
    }
    /** Adapt a result already computed, without issuing a second provider request. */
    public SearchResult toSearchResult(RagResult result) {
        return new SearchResult(getSource(), result.candidates().stream().limit(NB_MAX_RESULTS)
                .map(candidate -> new MatchingHSCode(HSCode.hsCode(candidate.code()), new MatchingScore(INDUSTRIAL_SCORE))).toList());
    }
    @Override public AnalyseResult analyse(String description, HSCode code) {
        return analysisDelegate.analyse(description, code);
    }
    @Override public Source getSource() { return Source.OpenAI_Hybrid; }
    public RagResult searchDetailed(String description) { return searchDetailed(description, NB_MAX_RESULTS); }

    public RagResult searchDetailed(String description, int topK) {
        require(description != null && !description.isBlank() && topK > 0, "Non-empty description and positive topK required");
        require(retrievalK >= topK, "retrievalK must be >= topK");
        var hits = index.search(embeddings.embed(description), retrievalK);
        if (hits.isEmpty()) return new RagResult("abstained", List.of(), List.of());

        var allowed = new TreeMap<String, HSCodeWithDescription>();
        for (var hit : hits) allowed.put(hit.hsCode().toDigits(), hit);
        var schema = SCHEMA.deepCopy();
        ((ObjectNode) schema.path("properties").path("candidates").path("items").path("properties").path("code"))
                .set("enum", MAPPER.valueToTree(allowed.keySet()));
        var input = MAPPER.createObjectNode().put("product_description", description);
        var provided = input.putArray("candidates");
        for (var hit : hits) provided.addObject().put("code", hit.hsCode().toDigits()).put("description", hit.description())
                .put("contextual_description", index.contextualDescription(hit));
        String instructions = PROMPT.replace("{top_k}", Integer.toString(topK));
        Answer answer = parseAnswer(generation.generate(instructions, encode(input), schema));

        var candidates = new ArrayList<RagResult.Candidate>();
        var seen = new HashSet<String>();
        for (int i = 0; i < Math.min(topK, answer.candidates().size()); i++) {
            var item = answer.candidates().get(i);
            if (allowed.containsKey(item.code()) && seen.add(item.code())) {
                candidates.add(new RagResult.Candidate(item.code(), i + 1,
                        allowed.get(item.code()).description(), RagResult.DEFAULT_SCORE, item.explanation()));
            }
        }
        if (!answer.candidates().isEmpty() && candidates.isEmpty())
            throw new HSCodeAnalysisException("All proposed codes were rejected");
        return new RagResult(answer.status(), candidates, answer.missingInformation());
    }
    private record Answer(String status, @JsonProperty("missing_information") List<String> missingInformation,
                          List<AnswerCandidate> candidates) {}
    private record AnswerCandidate(String code, String explanation) {}

    private static Answer parseAnswer(String text) {
        try { return MAPPER.readerFor(Answer.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(text); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new HSCodeAnalysisException("Invalid RAG JSON answer");
        }
    }
    private static InputStream openResource(String name) throws IOException {
        var stream = RagHSCodeAnalysisService.class.getResourceAsStream(name);
        if (stream == null) throw new FileNotFoundException("Missing RAG classpath resource: /"
                + RagHSCodeAnalysisService.class.getPackageName().replace('.', '/') + "/" + name);
        return stream;
    }
    private static String resource(String name) {
        try (var stream = openResource(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot read RAG resource: " + name, e); }
    }
}
