package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeAnalysisException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

class ReferenceCasesTest {
    @TempDir Path directory;
    @TestFactory Stream<DynamicTest> goldenReferenceCases() throws Exception {
        RagTestSupport.fixtures(directory);
        var index = RagTestSupport.index(directory);
        JsonNode cases;
        try (var stream = getClass().getResourceAsStream("/rag-fixtures/cases.json")) { cases = MAPPER.readTree(stream); }
        var tests = new ArrayList<DynamicTest>();
        for (JsonNode c : cases) tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> check(index, c)));
        return tests.stream();
    }
    private void check(PrecomputedEmbeddingIndex index, JsonNode c) {
        var embedCalls = new AtomicInteger(); var generateCalls = new AtomicInteger();
        RagEmbeddingClient embed = query -> {
            embedCalls.incrementAndGet();
            return new double[]{1, 0};
        };
        JsonNode expected = c.get("expected");
        RagGenerationClient generate = (instructions, input, schema) -> {
            generateCalls.incrementAndGet();
            JsonNode prompt = c.get("prompt");
            assertEquals(prompt.path("instructions").asText(), instructions);
            assertEquals(parse(prompt.path("input").asText()), parse(input));
            assertEquals(prompt.get("schema"), schema);
            assertFalse(input.contains("cosine"));
            assertFalse(parse(input).path("candidates").get(0).has("score"));
            return c.get("response").asText();
        };
        var service = new RagHSCodeAnalysisService(index, embed, generate, RagTestSupport.DELEGATE, c.get("retrieval_k").asInt());
        if (c.path("fails").asBoolean()) {
            assertThrows(HSCodeAnalysisException.class,
                    () -> service.searchDetailed(c.get("query").asText(), c.get("top_k").asInt()));
        } else {
            var result = service.searchDetailed(c.get("query").asText(), c.get("top_k").asInt());
            // Exact business JSON also ensures diagnostic fields are absent.
            assertEquals(expected, MAPPER.valueToTree(result));
            var adapted = service.toSearchResult(result);
            assertEquals(result.candidates().stream().limit(5).map(RagResult.Candidate::code).toList(),
                    adapted.matchingHSCodes().stream().map(hit -> hit.HSCode().toDigits()).toList());
            assertTrue(adapted.matchingHSCodes().stream().allMatch(hit -> hit.score().score() == 3));
        }
        assertEquals(c.get("embedding_calls").asInt(), embedCalls.get());
        assertEquals(c.get("generation_calls").asInt(), generateCalls.get(), "Mapping must not call the model again");
    }
}
