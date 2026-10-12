package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.tradeanalysis.domain.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

class RagServiceTest {
    @TempDir Path directory;
    PrecomputedEmbeddingIndex index;
    @BeforeEach void setup() throws Exception { RagTestSupport.fixtures(directory); index = RagTestSupport.index(directory); }
    private RagEmbeddingClient embed() { return query -> new double[]{1,0}; }
    private RagGenerationClient_v2 generate() {
        return (instructions, input, schema) -> {
            try (var stream = getClass().getResourceAsStream("/rag-fixtures/response.json")) { return new RagGenerationClient_v2.Answer(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new AssertionError(e); }
        };
    }
    @Test void publicSearchPreservesCompleteResultWithoutScores() {
        var service = new RagHSCodeAnalysisService(index, embed(), generate(), 20);
        var result = service.searchFromDescription("horses");
        assertEquals(Source.OpenAI_Hybrid, result.source());
        assertEquals(List.of("010129", "010121"), result.candidates().stream().map(Candidate_v2::code).toList());
    }
    @Test void providerFailurePropagatesToIndustrialCaller() {
        var failure = new IllegalStateException("model unavailable");
        RagGenerationClient_v2 generation = (instructions, input, schema) -> { throw failure; };
        var service = new RagHSCodeAnalysisService(index, embed(), generation, 20);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.searchFromDescription("horses")));
    }
    @Test void retrievalFailurePreventsGeneration() {
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("embedding unavailable");
        RagGenerationClient_v2 generation = (instructions, input, schema) -> { calls.incrementAndGet(); return null; };
        RagEmbeddingClient embeddings = query -> { throw failure; };
        var service = new RagHSCodeAnalysisService(index, embeddings, generation, 20);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.searchDetailed("horses")));
        assertEquals(0, calls.get());
    }
    @Test void malformedAnswerFailsInsteadOfReturningCandidates() {
        var service = new RagHSCodeAnalysisService(index, embed(), (i, u, s) -> new RagGenerationClient_v2.Answer("not JSON"), 20);
        assertThrows(HSCodeAnalysisException.class, () -> service.searchFromDescription("horses"));
    }
    @Test void unknownJsonPropertiesAreRejectedLikePoc() {
        RagGenerationClient_v2 generation = (i, u, s) -> new RagGenerationClient_v2.Answer("""
                {"status":"ok","missing_information":[],"extra":"ignored",
                 "candidates":[{"code":"010121","explanation":null,"extra":true}]}
                """);
        var service = new RagHSCodeAnalysisService(index, embed(), generation, 20);
        assertThrows(HSCodeAnalysisException.class, () -> service.searchDetailed("horses"));
    }
    @Test void invalidQueryAndRetrievalSizeFailBeforeCallingModels() {
        RagEmbeddingClient embeddings = query -> { throw new AssertionError("Unexpected embedding call"); };
        var service = new RagHSCodeAnalysisService(index, embeddings, generate(), 2);
        assertThrows(IllegalArgumentException.class, () -> service.searchDetailed("", 2));
        assertThrows(IllegalArgumentException.class, () -> service.searchDetailed("horses", 0));
        assertThrows(IllegalArgumentException.class, () -> service.searchDetailed("horses", 3));
    }
    @Test void constructorRejectsVectorizerConfigMismatch() {
        var client = new RagEmbeddingClient() {
            public Integer dimensions() { return 2; }
            public double[] embed(String query) { throw new AssertionError(); }
        };
        assertThrows(IllegalArgumentException.class, () -> new RagHSCodeAnalysisService(index, client, generate(),20));
    }
    @Test void sharedIndexAndServiceSupportConcurrentCallsWithoutSharedResultState() throws Exception {
        RagGenerationClient_v2 generation = (instructions, input, schema) -> new RagGenerationClient_v2.Answer(encode(Map.of(
                "status", "ok", "missing_information", List.of(), "candidates", List.of(Map.of(
                        "code", "010121", "explanation", parse(input).path("product_description").asText())))));
        var service = new RagHSCodeAnalysisService(index, embed(), generation,20);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<SearchResult_v2>>();
            for (int i=0; i<12; i++) { String description="horses " + i; tasks.add(() -> service.searchDetailed(description)); }
            var results=executor.invokeAll(tasks);
            for (int i=0; i<results.size(); i++) {
                var result = results.get(i).get();
                assertEquals(SearchResult_v2.Status.OK, result.status());
                assertEquals("horses " + i, result.candidates().getFirst().explanation());
            }
        }
    }

    @Test void needsInfoAndRefusalRetainQuestionsAndProvisionalCandidates() {
        var questions = List.of("Intended use?");
        var answer = new RagGenerationClient_v2.Answer(encode(Map.of("status", "needs_info",
                "missing_information", questions, "candidates", List.of(Map.of("code", "010129", "explanation", "Weak match")))));
        var service = new RagHSCodeAnalysisService(index, embed(), (a, b, c) -> answer, 20);
        var result = service.searchFromDescription("unclear merchandise");
        assertEquals(SearchResult_v2.Status.NEEDS_INFO, result.status());
        assertEquals(questions, result.missingInformation());
        assertEquals("Weak match", result.candidates().getFirst().explanation());
        service = new RagHSCodeAnalysisService(index, embed(), (a, b, c) -> new RagGenerationClient_v2.Refusal(), 20);
        result = service.searchDetailed("unclear merchandise", 2);
        assertEquals(SearchResult_v2.Status.NEEDS_INFO, result.status());
        assertEquals(List.of("010121", "010129"), result.candidates().stream().map(Candidate_v2::code).toList());
        assertEquals(List.of(1, 2), result.candidates().stream().map(Candidate_v2::rank).toList());
        assertTrue(result.candidates().stream().allMatch(c -> c.explanation() == null));
        assertEquals(List.of("Which material, composition, physical form, processing, or intended use best describes the merchandise?"), result.missingInformation());
        var json = MAPPER.valueToTree(result);
        assertEquals("needs_info", json.path("status").asText());
        assertFalse(json.path("candidates").get(0).has("score"));
        assertEquals(result, MAPPER.convertValue(json, SearchResult_v2.class));
        assertTrue(Arrays.stream(com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService_v2.class.getMethods())
                .noneMatch(m -> m.getName().equals("analyse")));
    }
    @Test void emptyRetrievalFailsWithoutGeneration() throws Exception {
        var empty = new PrecomputedEmbeddingIndex(new PrecomputedIndexResources(directory, RagTestSupport.hsCodeService()),
                new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<dev.langchain4j.data.segment.TextSegment>());
        var service = new RagHSCodeAnalysisService(empty, embed(), (a, b, c) -> { throw new AssertionError("Unexpected generation"); }, 20);
        assertThrows(HSCodeAnalysisException.class, () -> service.searchFromDescription("horses"));
    }
    @Test void malformedAndInconsistentAnswersFail() {
        for (String answer : List.of("null", "[]", "{}",
                "{\"status\":\"ok\",\"missing_information\":[],\"candidates\":[]}",
                "{\"status\":\"ok\",\"missing_information\":[\"Use?\"],\"candidates\":[{\"code\":\"010121\",\"explanation\":null}]}",
                "{\"status\":\"needs_info\",\"missing_information\":[],\"candidates\":[{\"code\":\"010121\",\"explanation\":null}]}",
                "{\"status\":\"needs_info\",\"missing_information\":[\" \"],\"candidates\":[{\"code\":\"010121\",\"explanation\":null}]}",
                "{\"status\":\"ok\",\"missing_information\":[],\"candidates\":[{\"code\":10121,\"explanation\":null}]}",
                "{\"status\":\"ok\",\"missing_information\":[],\"candidates\":[{\"code\":\"010121\"}]}",
                "{\"status\":\"ok\",\"missing_information\":[],\"candidates\":[{\"code\":\"010121\",\"explanation\":true}]}")) {
            var service = new RagHSCodeAnalysisService(index, embed(), (a, b, c) -> new RagGenerationClient_v2.Answer(answer), 20);
            assertThrows(HSCodeAnalysisException.class, () -> service.searchFromDescription("horses"), answer);
        }
    }
}
