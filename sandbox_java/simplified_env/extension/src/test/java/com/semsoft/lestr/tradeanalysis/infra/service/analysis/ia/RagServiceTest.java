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
    private RagEmbeddingClient embed() { return query -> new double[]{1,0}; }
    private RagGenerationClient generate() {
        return (instructions, input, schema) -> {
            try (var stream = getClass().getResourceAsStream("/rag-fixtures/response.json")) { return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
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
    @Test void providerFailurePropagatesToIndustrialCaller() {
        var failure = new IllegalStateException("model unavailable");
        RagGenerationClient generation = (instructions, input, schema) -> { throw failure; };
        var service = new RagHSCodeAnalysisService(index, embed(), generation, RagTestSupport.DELEGATE, 20);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.searchFromDescription("horses")));
    }
    @Test void retrievalFailurePreventsGeneration() {
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("embedding unavailable");
        RagGenerationClient generation = (instructions, input, schema) -> { calls.incrementAndGet(); return null; };
        RagEmbeddingClient embeddings = query -> { throw failure; };
        var service = new RagHSCodeAnalysisService(index, embeddings, generation, RagTestSupport.DELEGATE, 20);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.searchDetailed("horses")));
        assertEquals(0, calls.get());
    }
    @Test void malformedAnswerFailsInsteadOfReturningCandidates() {
        var service = new RagHSCodeAnalysisService(index, embed(), (i, u, s) -> "not JSON", RagTestSupport.DELEGATE, 20);
        assertThrows(HSCodeAnalysisException.class, () -> service.searchFromDescription("horses"));
    }
    @Test void unknownJsonPropertiesAreIgnoredLikeIndustrialCompletion() {
        RagGenerationClient generation = (i, u, s) -> """
                {"status":"ok","missing_information":[],"extra":"ignored",
                 "candidates":[{"code":"010121","explanation":null,"extra":true}]}
                """;
        var service = new RagHSCodeAnalysisService(index, embed(), generation, RagTestSupport.DELEGATE, 20);
        var result = service.searchDetailed("horses");
        assertEquals("010121", result.candidates().getFirst().code());
        assertNull(result.candidates().getFirst().explanation());
    }
    @Test void invalidQueryAndRetrievalSizeFailBeforeCallingModels() {
        RagEmbeddingClient embeddings = query -> { throw new AssertionError("Unexpected embedding call"); };
        var service = new RagHSCodeAnalysisService(index, embeddings, generate(), RagTestSupport.DELEGATE, 2);
        assertThrows(IllegalArgumentException.class, () -> service.searchDetailed("", 2));
        assertThrows(IllegalArgumentException.class, () -> service.searchDetailed("horses", 0));
        assertThrows(IllegalArgumentException.class, () -> service.searchDetailed("horses", 3));
    }
    @Test void constructorRejectsVectorizerConfigMismatch() {
        var client = new RagEmbeddingClient() {
            public Integer dimensions() { return 2; }
            public double[] embed(String query) { throw new AssertionError(); }
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
