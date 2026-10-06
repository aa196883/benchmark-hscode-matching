package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.*;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** RAG service using rag_v1. Generation order is preserved; fixed display scores never influence ranking. */
public final class RagHSCodeAnalysisService implements HSCodeAnalysisService {
    public static final String PROMPT_VERSION = "rag_v1";
    public static final int DEFAULT_RETRIEVAL_K = 20;
    public static final int INDUSTRIAL_SCORE = 3;
    private static final String INDEX_RESOURCE_DIRECTORY = "h6_2022/";
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
                                                     HSCodeAnalysisService analysisDelegate) throws IOException {
        var index = loadIndex();
        var defaults = OpenAiRagClient.Config.defaults();
        var config = new OpenAiRagClient.Config(properties.modelName(), defaults.maxOutputTokens(), null, null);
        var client = new OpenAiRagClient(properties.apiKey(), index.configuredDimensions(), config);
        return new RagHSCodeAnalysisService(index, client, client, analysisDelegate, DEFAULT_RETRIEVAL_K);
    }
    /** Loads the packaged catalogue and index; no file-system paths or temporary extraction are needed. */
    public static PrecomputedEmbeddingIndex loadIndex() throws IOException {
        var catalog = new RagCatalog(openResource(INDEX_RESOURCE_DIRECTORY + "catalog.jsonl"));
        String location = "classpath:/" + RagHSCodeAnalysisService.class.getPackageName().replace('.', '/')
                + "/" + INDEX_RESOURCE_DIRECTORY;
        return new PrecomputedEmbeddingIndex(location,
                name -> openResource(INDEX_RESOURCE_DIRECTORY + name), catalog);
    }
    @Override public SearchResult searchFromDescription(String description) {
        return toSearchResult(searchDetailed(description));
    }
    /** Adapt a result already computed, without issuing a second provider request. */
    public SearchResult toSearchResult(RagResult result) {
        if ("error".equals(result.status()))
            throw new HSCodeAnalysisException(result.error() == null ? "RAG failed" : result.error().path("message").asText("RAG failed"));
        return new SearchResult(getSource(), result.candidates().stream().limit(NB_MAX_RESULTS)
                .map(candidate -> new MatchingHSCode(HSCode.hsCode(candidate.code()), new MatchingScore(INDUSTRIAL_SCORE))).toList());
    }
    @Override public AnalyseResult analyse(String description, HSCode code) {
        return analysisDelegate.analyse(description, code);
    }
    @Override public Source getSource() { return Source.OpenAI_Hybrid; }
    public RagResult searchDetailed(String description) { return searchDetailed(description, NB_MAX_RESULTS); }

    public RagResult searchDetailed(String description, int topK) {
        long start = System.nanoTime();
        var meta = MAPPER.createObjectNode().put("approach", "rag")
                .put("prompt_version", PROMPT_VERSION).put("catalog_sha256", index.catalog().sha256())
                .put("edition", "2022").put("language", "en").put("top_k", topK).put("retrieval_k", retrievalK);
        var rejected = meta.putArray("rejected_candidates");
        var candidates = new ArrayList<RagResult.Candidate>();
        var missing = new ArrayList<String>();
        String status = "error";
        JsonNode error = null;
        require(description != null && !description.isBlank() && topK > 0, "Non-empty description and positive topK required");
        require(retrievalK >= topK, "retrievalK must be >= topK");
        meta.put("index_path", index.location()).set("index_manifest", index.manifest());
        long retrievalStart = System.nanoTime();
        var embedding = embeddings.embed(description);
        var hits = index.search(embedding, retrievalK);
        var retrieval = meta.putObject("retrieval").put("duration_seconds", seconds(retrievalStart));
        retrieval.set("query_vector", MAPPER.valueToTree(embedding));
        meta.set("retrieved_candidates", MAPPER.valueToTree(hits));
        if (hits.isEmpty()) {
            status = "abstained";
            meta.put("abstention_reason", "no_retrieved_candidates");
        } else {
            var allowed = new TreeSet<String>();
            for (var hit : hits) allowed.add(hit.code());
            var schema = SCHEMA.deepCopy();
            ((ObjectNode) schema.path("properties").path("candidates").path("items").path("properties").path("code"))
                    .set("enum", MAPPER.valueToTree(allowed));
            var input = MAPPER.createObjectNode().put("product_description", description);
            var provided = input.putArray("candidates");
            for (var hit : hits) provided.addObject().put("code", hit.code()).put("description", hit.description()).put("contextual_description", hit.contextualDescription());
            String instructions = PROMPT.replace("{top_k}", Integer.toString(topK)), userInput = encode(input);
            meta.putObject("prompt").put("instructions", instructions).put("input", userInput).set("schema", schema);
            long generationStart = System.nanoTime();
            String text = generation.generate(instructions, userInput, schema);
            meta.put("generation_duration_seconds", seconds(generationStart));
            Answer answer = parseAnswer(text);
            status = answer.status();
            meta.put("model_status", status);
            missing.addAll(answer.missingInformation());
            var seen = new HashSet<String>();
            int rank = 0;
            for (var item : answer.candidates()) {
                rank++;
                String code = item.code();
                String explanation = item.explanation();
                String reason = index.catalog().rejectionReason(code);
                if (reason == null && !allowed.contains(code)) reason = "not_retrieved";
                if (reason == null && seen.contains(code)) reason = "duplicate";
                if (reason == null && rank > topK) reason = "beyond_top_k";
                seen.add(code);
                if (reason != null) {
                    rejected.addObject().put("rank", rank).put("code", code).put("explanation", explanation).put("reason", reason);
                } else {
                    candidates.add(new RagResult.Candidate(code, rank, index.catalog().row(code).description(), RagResult.DEFAULT_SCORE,
                            "constant", explanation, List.of("catalog:2022:" + code)));
                }
            }
            if (!answer.candidates().isEmpty() && candidates.isEmpty()) {
                status = "error"; error = error("invalid_candidates", "All proposed codes were rejected");
            }
        }
        meta.put("duration_seconds", seconds(start));
        return new RagResult(status, candidates, missing, error, meta);
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
    private static JsonNode error(String kind, String message) { return MAPPER.createObjectNode().put("kind", kind).put("message", message); }
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
