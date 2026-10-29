package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.tradeanalysis.infra.service.HSCodeServiceImpl;
import com.semsoft.lestr.common.test.Utils;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.infra.configuration.OpenAIProperties;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Objects;

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

    private RagHSCodeAnalysisService ragHSCodeService;

    @BeforeAll
    void initializeService() throws Exception {
        var openAIProperties = new OpenAIProperties(Objects.requireNonNull(Utils.getSecret("OPENAI-API")));
        // One fresh database for the entire class, never the Compose database.
        var admin = datasource(POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(admin).locations("classpath:db/migration").load().migrate();
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            // Credentials and grants belong to this disposable test, not to production migrations.
            statement.execute("CREATE ROLE rag_import LOGIN PASSWORD 'test-import'; "
                    + "CREATE ROLE rag_reader LOGIN PASSWORD 'test-reader'; "
                    + "GRANT USAGE ON SCHEMA rag TO rag_import, rag_reader; "
                    + "GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA rag TO rag_import; "
                    + "GRANT SELECT ON ALL TABLES IN SCHEMA rag TO rag_reader; "
                    + "ALTER ROLE rag_reader SET default_transaction_read_only = on");
        }
        var hsCodeService = new HSCodeServiceImpl();
        var resources = PrecomputedIndexResources.packaged(hsCodeService);
        assertTrue(RagIndexImporter.importIndex(datasource("rag_import", "test-import"), resources));
        var reader = datasource("rag_reader", "test-reader");

        ragHSCodeService = RagHSCodeAnalysisService.construct(
                openAIProperties, reader, hsCodeService);
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

        com.semsoft.lestr.tradeanalysis.domain.model.SearchResult result =
                ragHSCodeService.searchFromDescription(description);

        log.info(
                "Candidates for '{}': {}",
                description,
                result.candidates()
        );

        HSCode expected = HSCode.hsCode(expectedHSCode);

        boolean found = result.candidates()
                .stream()
                .anyMatch(candidate ->
                        candidate.code().equals(expected.toDigits())
                );

        assertTrue(
                found,
                "Expected HS code " + expectedHSCode
                        + " among candidates for '" + description + "'"
        );
    }
}
