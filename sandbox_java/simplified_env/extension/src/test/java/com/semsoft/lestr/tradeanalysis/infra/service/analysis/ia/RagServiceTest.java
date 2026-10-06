package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
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
    private RagEmbeddingClient embed() { return query -> new RagEmbeddingClient.Response(new double[]{1,0}, PrecomputedEmbeddingIndex.MODEL, MAPPER.createObjectNode()); }
    private RagGenerationClient generate() {
        return (instructions, input, schema) -> {
            try (var stream = getClass().getResourceAsStream("/rag-fixtures/response.json")) { return MAPPER.readTree(stream); }
            catch (Exception e) { throw new AssertionError(e); }
        };
    }
    @Test void industrialSearchAndAnalyseUseTheRightSourcesAndScores() {
        var service = new RagHSCodeAnalysisService(index, embed(), generate(), RagTestSupport.DELEGATE, 20);
        var result = service.searchFromDescription("horses");
        assertEquals(Source.OpenAI_Hybrid, result.source());
        assertEquals(List.of("010129", "010121"), result.matchingHSCodes().stream().map(hit -> hit.HSCode().toDigits()).toList());
        assertTrue(result.matchingHSCodes().stream().allMatch(hit -> hit.score().score() == 3));
        assertEquals(new AnalyseResult(Source.Verbatim, "horses 010121"), service.analyse("horses", HSCode.hsCode("010121")));
    }
    @Test void providerFailurePreservesStageAndRetrievalAndTriggersIndustrialException() {
        var count = new AtomicInteger();
        RagGenerationClient failure = (instructions, input, schema) -> {
            count.incrementAndGet(); throw new RagProviderException("http_error", "fixture failure", 429, "request-fixture");
        };
        var service = new RagHSCodeAnalysisService(index, embed(), failure, RagTestSupport.DELEGATE, 20);
        var result = service.searchDetailed("horses");
        assertEquals("error", result.status());
        assertEquals("generation", result.metadata().get("error_stage").asText());
        assertEquals(429, result.error().get("status_code").asInt());
        assertEquals(3, result.metadata().get("retrieved_candidates").size());
        assertTrue(result.metadata().has("generation_duration_seconds"));
        assertThrows(HSCodeAnalysisException.class, () -> service.toSearchResult(result));
        assertEquals(1, count.get());
    }
    @Test void retrievalFailureAndWrongResponseModelPreventGeneration() {
        var calls = new AtomicInteger();
        RagGenerationClient generation = (instructions, input, schema) -> { calls.incrementAndGet(); return null; };
        RagEmbeddingClient failure = query -> { throw new RagProviderException("connection_error", "fixture", null, null); };
        for (var client : List.of(failure, (RagEmbeddingClient) query -> new RagEmbeddingClient.Response(new double[]{1,0}, "wrong-model", null),
                (RagEmbeddingClient) query -> new RagEmbeddingClient.Response(new double[]{1}, PrecomputedEmbeddingIndex.MODEL, null))) {
            var service = new RagHSCodeAnalysisService(index, client, generation, RagTestSupport.DELEGATE, 20);
            var result = service.searchDetailed("horses");
            assertEquals("error", result.status());
            assertEquals("retrieval", result.metadata().get("error_stage").asText());
        }
        assertEquals(0, calls.get());
    }
    @Test void constructorRejectsVectorizerConfigMismatch() {
        var client = new RagEmbeddingClient() {
            public Integer dimensions() { return 2; }
            public Response embed(String query) { throw new AssertionError(); }
        };
        assertThrows(IllegalArgumentException.class, () -> new RagHSCodeAnalysisService(index, client, generate(), RagTestSupport.DELEGATE,20));
    }
    @Test void sharedIndexAndServiceSupportConcurrentCallsWithoutSharedResultState() throws Exception {
        var service = new RagHSCodeAnalysisService(index, embed(), generate(), RagTestSupport.DELEGATE,20);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<RagResult>>();
            for (int i=0; i<12; i++) { String description="horses " + i; tasks.add(() -> service.searchDetailed(description)); }
            var results=executor.invokeAll(tasks);
            for (int i=0; i<results.size(); i++) {
                var result = results.get(i).get();
                assertEquals("ok", result.status());
                assertEquals("horses " + i, parse(result.metadata().path("prompt").path("input").asText()).path("product_description").asText());
                assertEquals(2.5, result.candidates().getFirst().score());
            }
        }
    }
}
