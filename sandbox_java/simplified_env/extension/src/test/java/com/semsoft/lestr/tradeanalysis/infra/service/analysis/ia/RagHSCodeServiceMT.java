package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.AnalyseResult;
import com.semsoft.lestr.tradeanalysis.domain.model.SearchResult;
import com.semsoft.lestr.tradeanalysis.domain.model.Source;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_MT", matches = "true")
class RagHSCodeServiceMT {

    private static final Logger log =
            LoggerFactory.getLogger(RagHSCodeServiceMT.class);

    /*
     * searchFromDescription() does not use the analysis delegate.
     * This stub exists only because RagHSCodeAnalysisService requires one.
     */
    private static final HSCodeAnalysisService UNUSED_ANALYSIS_DELEGATE =
            new HSCodeAnalysisService() {

                @Override
                public SearchResult searchFromDescription(String description) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public AnalyseResult analyse(String description, HSCode hsCode) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Source getSource() {
                    throw new UnsupportedOperationException();
                }
            };

    private RagHSCodeAnalysisService ragHSCodeService;

    @BeforeAll
    void initializeService() throws Exception {
        String apiKey = Objects.requireNonNull(System.getenv("OPENAI_API_KEY"),
                "Set OPENAI_API_KEY before running this manual test");
        if (apiKey.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY must not be blank");
        ragHSCodeService = RagHSCodeAnalysisService.construct(
                new OpenAIProperties(apiKey), UNUSED_ANALYSIS_DELEGATE);
    }

    @Test
    void serviceCanBeInstantiated() {
        assertNotNull(
                ragHSCodeService,
                "RAG HS code service should be instantiated"
        );
    }

    @Test
    void searchBanana() {
        assertContainsHSCode("banana", "080390");
    }

    @Test
    void searchToluene() {
        assertContainsHSCode("toluene", "290230");
    }

    @Test
    void searchTShirt() {
        assertContainsHSCode("T-shirt", "610910");
    }

    private void assertContainsHSCode(
            String description,
            String expectedHSCode
    ) {
        log.info(
                "Searching for '{}' - expected HS code: {}",
                description,
                expectedHSCode
        );

        SearchResult result =
                ragHSCodeService.searchFromDescription(description);

        log.info(
                "Candidates for '{}': {}",
                description,
                result.matchingHSCodes()
        );

        HSCode expected = HSCode.hsCode(expectedHSCode);

        boolean found = result.matchingHSCodes()
                .stream()
                .anyMatch(candidate ->
                        candidate.HSCode().equals(expected)
                );

        assertTrue(
                found,
                "Expected HS code " + expectedHSCode
                        + " among candidates for '" + description + "'"
        );
    }
}
