package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag.RagJson.*;

/** Port of rag_v1. Generation order is preserved; fixed display scores never influence ranking. */
public final class RagHSCodeAnalysisService implements HSCodeAnalysisService {
    public static final String PROMPT_VERSION = "rag_v1";
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
        require(PrecomputedEmbeddingIndex.MODEL.equals(embeddings.model()) && Objects.equals(index.configuredDimensions(), embeddings.dimensions()), "Query vectorizer config must match the index");
    }
    public static RagHSCodeAnalysisService construct(Path catalogPath, Path indexPath,
                                                     OpenAIProperties properties, HSCodeAnalysisService analysisDelegate) throws IOException {
        var index = new PrecomputedEmbeddingIndex(indexPath, new RagCatalog(catalogPath));
        var defaults = OpenAiRagClient.Config.defaults();
        var config = new OpenAiRagClient.Config(properties.modelName(), defaults.maxOutputTokens(), null, null, defaults.timeout());
        var client = new OpenAiRagClient(properties.apiKey(), index.configuredDimensions(), config);
        return new RagHSCodeAnalysisService(index, client, client, analysisDelegate, DEFAULT_RETRIEVAL_K);
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
        var meta = MAPPER.createObjectNode().put("approach", "rag").put("provider", generation.provider())
                .put("prompt_version", PROMPT_VERSION).put("catalog_sha256", index.catalog().sha256())
                .put("edition", "2022").put("language", "en").put("top_k", topK).put("retrieval_k", retrievalK);
        meta.set("config", generation.configuration());
        var rejected = meta.putArray("rejected_candidates");
        var candidates = new ArrayList<RagResult.Candidate>();
        var missing = new ArrayList<String>();
        String status = "error";
        JsonNode error = null;
        try {
            require(description != null && !description.isBlank() && topK > 0, "Non-empty description and positive topK required");
            require(retrievalK >= topK, "retrievalK must be >= topK");
            meta.put("index_path", index.directory().toString()).set("index_manifest", index.manifest());
            meta.put("error_stage", "retrieval");
            long retrievalStart = System.nanoTime();
            var embedding = embeddings.embed(description);
            require(embedding != null && PrecomputedEmbeddingIndex.MODEL.equals(embedding.model()), "Returned embedding model differs from the index");
            var hits = index.search(embedding.vector(), retrievalK);
            var retrieval = meta.putObject("retrieval").put("response_model", embedding.model()).put("duration_seconds", seconds(retrievalStart));
            retrieval.set("usage", embedding.usage());
            retrieval.set("query_vector", MAPPER.valueToTree(embedding.vector()));
            meta.set("retrieved_candidates", MAPPER.valueToTree(hits));
            if (hits.isEmpty()) {
                status = "abstained";
                meta.put("abstention_reason", "no_retrieved_candidates");
            } else {
                var allowed = new TreeSet<String>();
                for (var hit : hits) require(index.catalog().rejectionReason(hit.code()) == null && allowed.add(hit.code()), "Invalid or duplicated retrieved candidates");
                var schema = SCHEMA.deepCopy();
                ((ObjectNode) schema.path("properties").path("candidates").path("items").path("properties").path("code"))
                        .set("enum", MAPPER.valueToTree(allowed));
                var input = MAPPER.createObjectNode().put("product_description", description);
                var provided = input.putArray("candidates");
                for (var hit : hits) provided.addObject().put("code", hit.code()).put("description", hit.description()).put("contextual_description", hit.contextualDescription());
                String instructions = PROMPT.replace("{top_k}", Integer.toString(topK)), userInput = encode(input);
                meta.putObject("prompt").put("instructions", instructions).put("input", userInput).set("schema", schema);
                meta.put("error_stage", "generation");
                long generationStart = System.nanoTime();
                JsonNode raw;
                try { raw = generation.generate(instructions, userInput, schema.deepCopy()); }
                finally { meta.put("generation_duration_seconds", seconds(generationStart)); }
                meta.put("error_stage", "validation");
                meta.set("raw_response", raw);
                require(raw != null && raw.isObject(), "Invalid generation response");
                meta.set("usage", raw.get("usage")); meta.set("response_model", raw.get("model"));
                require("completed".equals(raw.path("status").asText()), "Generation response not completed");
                var refusals = MAPPER.createArrayNode();
                var text = new StringBuilder();
                require(raw.path("output").isArray(), "Missing response output");
                for (var item : raw.get("output")) {
                    if (!"message".equals(item.path("type").asText())) continue;
                    require(item.path("content").isArray(), "Invalid message content");
                    for (var part : item.get("content")) {
                        if ("refusal".equals(part.path("type").asText()))
                            refusals.add(part.get("refusal"));
                        if ("output_text".equals(part.path("type").asText())) text.append(RagJson.text(part, "text"));
                    }
                }
                if (!refusals.isEmpty()) {
                    meta.set("refusals", refusals); status = "abstained";
                } else {
                    JsonNode answer = parseAnswer(text.toString());
                    status = answer.get("status").textValue();
                    meta.put("model_status", status);
                    for (var question : answer.get("missing_information")) missing.add(question.textValue());
                    var seen = new HashSet<String>();
                    int rank = 0;
                    for (var item : answer.get("candidates")) {
                        rank++;
                        String code = item.get("code").textValue();
                        String explanation = item.get("explanation").isNull() ? null : item.get("explanation").textValue();
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
                    if (!answer.get("candidates").isEmpty() && candidates.isEmpty()) {
                        status = "error"; error = error("invalid_candidates", "All proposed codes were rejected");
                    }
                }
            }
        } catch (RagProviderException e) {
            status = "error"; error = e.details();
        } catch (IllegalArgumentException e) {
            status = "error"; error = error("validation_error", e.getMessage());
        } finally {
            if (error == null) meta.remove("error_stage");
            meta.put("duration_seconds", seconds(start));
        }
        return new RagResult(status, candidates, missing, error, meta);
    }
    static JsonNode parseAnswer(String text) {
        JsonNode answer = parse(text);
        require(fields(answer, Set.of("status", "missing_information", "candidates")), "Invalid response object");
        String status = RagJson.text(answer, "status");
        require(Set.of("ok", "needs_info", "abstained").contains(status), "Invalid status");
        var missing = answer.get("missing_information");
        require(missing.isArray(), "Invalid missing_information");
        for (var item : missing) require(item.isTextual() && !item.textValue().isBlank(), "Invalid clarification question");
        var candidates = answer.get("candidates");
        require(candidates.isArray(), "Invalid candidates array");
        for (var item : candidates) {
            require(fields(item, Set.of("code", "explanation")), "Invalid candidate object");
            require(item.get("code").isTextual() && (item.get("explanation").isNull() || item.get("explanation").isTextual()), "Invalid candidate fields");
        }
        require(!"ok".equals(status) || (!candidates.isEmpty() && missing.isEmpty()), "Inconsistent ok status");
        require(!"needs_info".equals(status) || !missing.isEmpty(), "needs_info requires questions");
        require(!"abstained".equals(status) || candidates.isEmpty(), "Abstention cannot contain candidates");
        return answer;
    }
    private static boolean fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) return false;
        var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add);
        return names.equals(expected);
    }
    private static JsonNode error(String kind, String message) { return MAPPER.createObjectNode().put("kind", kind).put("message", message); }
    private static String resource(String name) {
        try (var stream = RagHSCodeAnalysisService.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing RAG resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot read RAG resource: " + name, e); }
    }
}
