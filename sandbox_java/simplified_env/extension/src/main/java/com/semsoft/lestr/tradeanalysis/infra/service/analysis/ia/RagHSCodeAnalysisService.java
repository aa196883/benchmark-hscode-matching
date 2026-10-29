package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
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

/** RAG rag_v4: complete search results, with independent confidence ratings. */
public final class RagHSCodeAnalysisService implements HSCodeAnalysisService {
    public static final int DEFAULT_RETRIEVAL_K = 20;
    private static final String PROMPT = resource("rag_v4.txt");
    private static final JsonNode SCHEMA = parse(resource("rag_v4.schema.json"));
    private final PrecomputedEmbeddingIndex index;
    private final RagEmbeddingClient embeddings;
    private final RagGenerationClient generation;
    private final int retrievalK;

    public RagHSCodeAnalysisService(PrecomputedEmbeddingIndex index, RagEmbeddingClient embeddings,
                                   RagGenerationClient generation, int retrievalK) {
        this.index = Objects.requireNonNull(index);
        this.embeddings = Objects.requireNonNull(embeddings);
        this.generation = Objects.requireNonNull(generation);
        this.retrievalK = retrievalK;
        require(Objects.equals(index.configuredDimensions(), embeddings.dimensions()), "Query vectorizer config must match the index");
    }
    public static RagHSCodeAnalysisService construct(OpenAIProperties properties,
                                                     DataSource datasource, HSCodeService hsCodeService) throws IOException, SQLException {
        var index = loadIndex(datasource, hsCodeService);
        var defaults = OpenAiRagClient.Config.defaults();
        var config = new OpenAiRagClient.Config(properties.modelName(), defaults.maxOutputTokens(), null, null);
        var client = new OpenAiRagClient(properties.apiKey(), index.configuredDimensions(), config);
        return new RagHSCodeAnalysisService(index, client, client, DEFAULT_RETRIEVAL_K);
    }
    /** Checks the database against HS service descriptions and packaged manifest; does not read or import vectors. */
    public static PrecomputedEmbeddingIndex loadIndex(DataSource datasource, HSCodeService hsCodeService) throws IOException, SQLException {
        return new PrecomputedEmbeddingIndex(datasource, PrecomputedIndexResources.packaged(hsCodeService));
    }
    @Override public SearchResult searchFromDescription(String description) {
        return searchDetailed(description);
    }
    @Override public Source getSource() { return Source.OpenAI_Hybrid; }
    public SearchResult searchDetailed(String description) { return searchDetailed(description, NB_MAX_RESULTS); }

    public SearchResult searchDetailed(String description, int topK) {
        require(description != null && !description.isBlank() && topK > 0, "Non-empty description and positive topK required");
        require(retrievalK >= topK, "retrievalK must be >= topK");
        var hits = index.search(embeddings.embed(description), retrievalK);
        if (hits.isEmpty()) throw new HSCodeAnalysisException("No candidates retrieved for RAG ranking");

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
        var generated = generation.generate(instructions, encode(input), schema);
        if (generated instanceof RagGenerationClient.Refusal) {
            return new SearchResult(getSource(), SearchResult.Status.NEEDS_INFO, List.of(), List.of(
                    "Which material, composition, physical form, processing, or intended use best describes the merchandise?"));
        }
        if (!(generated instanceof RagGenerationClient.Answer generatedAnswer))
            throw new HSCodeAnalysisException("Missing RAG generation result");
        Answer answer = parseAnswer(generatedAnswer.text());

        var candidates = new ArrayList<Candidate>();
        var seen = new HashSet<String>();
        for (int i = 0; i < Math.min(topK, answer.candidates().size()); i++) {
            var item = answer.candidates().get(i);
            if (allowed.containsKey(item.code()) && seen.add(item.code())) {
                candidates.add(new Candidate(item.code(), i + 1,
                        allowed.get(item.code()).description(), item.explanation(), item.rating()));
            }
        }
        if (!answer.candidates().isEmpty() && candidates.isEmpty())
            throw new HSCodeAnalysisException("All proposed codes were rejected");
        return new SearchResult(getSource(), answer.status().equals("ok")
                ? SearchResult.Status.OK : SearchResult.Status.NEEDS_INFO, candidates, answer.missingInformation());
    }
    private record Answer(String status, List<String> missingInformation,
                          List<AnswerCandidate> candidates) {}
    private record AnswerCandidate(String code, String explanation, double rating) {}

    private static Answer parseAnswer(String text) {
        try {
            JsonNode root = MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            checkFields(root, Set.of("status", "missing_information", "candidates"));
            var status = root.get("status");
            if (!status.isTextual() || !Set.of("ok", "needs_info").contains(status.textValue())) invalidAnswer();
            var missing = root.get("missing_information");
            var items = root.get("candidates");
            if (!missing.isArray() || !items.isArray()) invalidAnswer();
            var questions = new ArrayList<String>();
            for (var question : missing) {
                if (!question.isTextual() || question.textValue().isBlank()) invalidAnswer();
                questions.add(question.textValue());
            }
            if (status.textValue().equals("ok") ? (items.isEmpty() || !questions.isEmpty()) : questions.isEmpty()) invalidAnswer();
            var candidates = new ArrayList<AnswerCandidate>();
            for (var item : items) {
                checkFields(item, Set.of("code", "explanation", "rating"));
                if (!item.get("code").isTextual()
                        || !item.get("explanation").isTextual() || item.get("explanation").textValue().isBlank()
                        || !item.get("rating").isNumber()) invalidAnswer();
                double rating = item.get("rating").doubleValue();
                if (!Double.isFinite(rating) || rating < 1 || rating > 5
                        || (!candidates.isEmpty() && candidates.getLast().rating() < rating)) invalidAnswer();
                candidates.add(new AnswerCandidate(item.get("code").textValue(), item.get("explanation").textValue(), rating));
            }
            return new Answer(status.textValue(), questions, candidates);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new HSCodeAnalysisException("Invalid RAG JSON answer");
        }
    }
    private static void checkFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) invalidAnswer();
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) invalidAnswer();
    }
    private static void invalidAnswer() { throw new HSCodeAnalysisException("Invalid RAG JSON answer"); }
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
