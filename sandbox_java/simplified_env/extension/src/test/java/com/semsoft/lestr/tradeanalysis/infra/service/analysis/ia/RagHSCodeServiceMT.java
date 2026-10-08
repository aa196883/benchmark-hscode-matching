package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semsoft.lestr.tradeanalysis.infra.service.HSCodeServiceImpl;
import com.semsoft.lestr.common.test.Utils;
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
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Self-contained database lifecycle; secret loading follows the industrial manual tests. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagHSCodeServiceMT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trade_analysis")
            .withUsername("rag_test_admin")
            .withPassword("test-admin");

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
        var openAIProperties = new OpenAIProperties(Objects.requireNonNull(Utils.getSecret("OPENAI-API")));
        // One fresh database for the entire class, never the Compose database.
        try (var connection = datasource(POSTGRES.getUsername(), POSTGRES.getPassword()).getConnection();
             var statement = connection.createStatement();
             var bootstrap = Objects.requireNonNull(RagIndexImporter.class.getResourceAsStream("db/bootstrap.sql"))) {
            statement.execute(new String(bootstrap.readAllBytes(), StandardCharsets.UTF_8));
            // Test-only credentials, scoped to this disposable container.
            statement.execute("ALTER ROLE rag_import PASSWORD 'test-import'; "
                    + "ALTER ROLE rag_reader PASSWORD 'test-reader'");
        }
        var hsCodeService = new HSCodeServiceImpl();
        var resources = PrecomputedIndexResources.packaged(hsCodeService);
        assertTrue(RagIndexImporter.importIndex(datasource("rag_import", "test-import"), resources));
        var reader = datasource("rag_reader", "test-reader");
        assertDatabaseMatchesVectors(reader, resources);

        ragHSCodeService = RagHSCodeAnalysisService.construct(
                openAIProperties, UNUSED_ANALYSIS_DELEGATE, reader, hsCodeService);
    }

    private static DataSource datasource(String user, String password) {
        var datasource = new PGSimpleDataSource();
        datasource.setUrl(POSTGRES.getJdbcUrl());
        datasource.setUser(user);
        datasource.setPassword(password);
        datasource.setConnectTimeout(5);
        datasource.setSocketTimeout(60);
        return datasource;
    }

    /** Compare every stored component with the raw file, independently of the importer's vector reader. */
    private static void assertDatabaseMatchesVectors(DataSource reader, PrecomputedIndexResources resources)
            throws Exception {
        var json = new ObjectMapper();
        try (var stream = Objects.requireNonNull(RagIndexImporter.class.getResourceAsStream("h6_2022/vectors.jsonl"));
             var lines = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
             var connection = reader.getConnection()) {
            connection.setAutoCommit(false); // Enable the JDBC cursor instead of buffering the whole index.
            try (var statement = connection.prepareStatement(
                    "SELECT metadata->>'code', embedding::text FROM rag.embeddings ORDER BY metadata->>'code'")) {
                statement.setFetchSize(64);
                try (var result = statement.executeQuery()) {
                    int count = 0;
                    String line;
                    while ((line = lines.readLine()) != null) {
                        var source = json.readTree(line);
                        String code = source.get("code").asText();
                        assertTrue(result.next(), "Missing database vector for " + code);
                        assertEquals(code, result.getString(1), "Unexpected database code/order");
                        float[] expected = json.treeToValue(source.get("vector"), float[].class);
                        assertEquals(resources.dimensions, expected.length, "Source dimensions for " + code);
                        // The disk format is raw; production stores L2-normalized float32 vectors.
                        double squaredNorm = 0;
                        for (float value : expected) squaredNorm += (double) value * value;
                        double norm = Math.sqrt(squaredNorm);
                        for (int i = 0; i < expected.length; i++) expected[i] = (float) (expected[i] / norm);
                        float[] actual = json.readValue(result.getString(2), float[].class);
                        // Numeric equality with zero tolerance: PostgreSQL can render -0.0 as 0.0.
                        assertArrayEquals(expected, actual, 0.0f, "Stored vector differs from vectors.jsonl for " + code);
                        count++;
                    }
                    assertFalse(result.next(), "Unexpected extra database vector");
                    assertEquals(resources.size(), count, "Database/source vector count");
                    log.info("Verified all {} vectors ({} dimensions) against vectors.jsonl", count, resources.dimensions);
                }
            }
        }
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
