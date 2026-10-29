package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.semsoft.lestr.tradeanalysis.infra.service.HSCodeServiceImpl;
import com.semsoft.lestr.tradeanalysis.domain.model.HSNomenclature;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real pgvector; no Spring, external database, API key or provider request. */
@Testcontainers
class PgVectorIndexIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trade_analysis");
    @TempDir Path directory;

    @BeforeAll static void bootstrap() throws Exception {
        try (var stream = RagIndexImporter.class.getResourceAsStream("db/bootstrap.sql")) {
            execute(admin(), new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
        }
        // These credentials exist only inside this disposable test container.
        execute(admin(), "ALTER ROLE rag_import PASSWORD 'test-import'; ALTER ROLE rag_reader PASSWORD 'test-reader'");
    }
    @BeforeEach void reset() throws Exception {
        migrateFreshSchema();
        // Small fixture vectors; production V1 remains unchanged at 1536 dimensions.
        execute(admin(), "ALTER TABLE rag.embeddings ALTER COLUMN embedding TYPE vector(2)");
        RagTestSupport.fixtures(directory);
    }
    private static void migrateFreshSchema() throws Exception {
        execute(admin(), "DROP SCHEMA rag CASCADE; DROP TABLE IF EXISTS public.flyway_schema_history");
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(admin())
                .locations("classpath:db/migration").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        execute(admin(), "GRANT USAGE ON SCHEMA rag TO rag_import, rag_reader; "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON rag.embeddings TO rag_import; "
                + "GRANT SELECT ON rag.embeddings TO rag_reader");
    }
    private PrecomputedIndexResources resources() throws IOException {
        return new PrecomputedIndexResources(directory, RagTestSupport.hsCodeService());
    }
    private static PGSimpleDataSource datasource(String user, String password) {
        var source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl()); source.setUser(user); source.setPassword(password);
        source.setConnectTimeout(5); source.setSocketTimeout(30);
        return source;
    }
    private static DataSource admin() { return datasource(POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private static DataSource importer() { return datasource("rag_import", "test-import"); }
    private static DataSource reader() { return datasource("rag_reader", "test-reader"); }
    private static void execute(DataSource source, String sql) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static String scalar(String sql) throws SQLException {
        try (var connection = admin().getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) { result.next(); return result.getString(1); }
    }
    private static List<String> codes(PrecomputedEmbeddingIndex index, double[] query, int k) {
        return index.search(query, k).stream().map(row -> row.hsCode().toDigits()).toList();
    }

    @Test void flywayV1SupportsExternallyLoadedDataWithoutManifest() throws Exception {
        migrateFreshSchema();
        double[] vector = new double[1536]; vector[0] = 1;
        // External loader: arbitrary UUID/text and only one row of the catalogue.
        execute(admin(), "INSERT INTO rag.embeddings VALUES ('" + UUID.randomUUID()
                + "', '" + encode(vector) + "', 'Externally loaded description', '{\"code\":\"010121\"}')");
        var index = RagHSCodeAnalysisService.loadIndex(reader(), RagTestSupport.hsCodeService());
        assertEquals(List.of("010121"), codes(index, vector, 3));
        assertEquals("1", scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'rag'"));
        assertFalse(RagIndexImporter.importIndex(importer(), resources()));
        assertThrows(SQLException.class, () -> execute(reader(), "DELETE FROM rag.embeddings"));
        execute(importer(), "DELETE FROM rag.embeddings");
        assertTrue(codes(index, vector, 3).isEmpty());
        rewriteVectors(encode(vector));
        var manifest = (ObjectNode) parse(Files.readString(directory.resolve("manifest.json")));
        manifest.put("dimensions", 1536);
        Files.writeString(directory.resolve("manifest.json"), encode(manifest));
        assertTrue(RagIndexImporter.importIndex(importer(), resources()));
        assertEquals(3, codes(index, vector, 3).size());
    }

    @Test void importsReopensAndRunsRagWithReadOnlyRole() throws Exception {
        var resources = resources();
        assertTrue(RagIndexImporter.importIndex(importer(), resources));
        var index = new PrecomputedEmbeddingIndex(reader(), resources);
        assertEquals(3, index.size()); assertEquals(2, index.dimensions()); assertNull(index.configuredDimensions());
        assertEquals(List.of("010121", "010129", "010130"), codes(index, new double[]{1, 0}, 20));
        assertEquals(List.of("010130", "010129", "010121"), codes(index, new double[]{-100, 0}, 3));
        RagEmbeddingClient embeddings = new RagEmbeddingClient() {
            public Integer dimensions() { return null; }
            public double[] embed(String text) { return new double[]{1, 0}; }
        };
        String answer = Files.readString(Path.of("src/test/resources/rag-fixtures/response.json"));
        var service = new RagHSCodeAnalysisService(index, embeddings, (instructions, input, schema) -> new RagGenerationClient.Answer(answer),
                3);
        var detailed = service.searchDetailed("Live horses", 2);
        assertEquals(List.of("010129", "010121"), detailed.candidates().stream().map(Candidate::code).toList());
        assertEquals("OpenAI_Hybrid", detailed.source().name());
        // No vector resource is opened by a new runtime instance.
        var readOnlyResources = new PrecomputedIndexResources(name -> {
            assertEquals("manifest.json", name);
            return Files.newInputStream(directory.resolve(name));
        }, RagTestSupport.hsCodeService());
        assertEquals(codes(index, new double[]{1, 0}, 2),
                codes(new PrecomputedEmbeddingIndex(reader(), readOnlyResources), new double[]{1, 0}, 2));
    }

    @Test void readerCannotWriteEvenWithTransactionReadOnlyDisabled() throws Exception {
        RagIndexImporter.importIndex(importer(), resources());
        for (String sql : List.of("DELETE FROM rag.embeddings", "UPDATE rag.embeddings SET text = 'changed'",
                "CREATE TABLE rag.forbidden (id int)",
                "CREATE TABLE public.forbidden (id int)", "CREATE TEMP TABLE forbidden (id int)")) {
            try (var connection = reader().getConnection(); var statement = connection.createStatement()) {
                statement.execute("SET default_transaction_read_only = off");
                var error = assertThrows(SQLException.class, () -> statement.execute(sql), sql);
                assertEquals("42501", error.getSQLState(), sql);
            }
        }
        assertEquals("3", scalar("SELECT count(*) FROM rag.embeddings"));
    }

    @Test void populatedTableIsTrustedAndImportDoesNotWrite() throws Exception {
        RagIndexImporter.importIndex(importer(), resources());
        String before = scalar("SELECT string_agg(xmin::text, ',' ORDER BY embedding_id) FROM rag.embeddings");
        assertFalse(RagIndexImporter.importIndex(importer(), resources()));
        assertEquals(before, scalar("SELECT string_agg(xmin::text, ',' ORDER BY embedding_id) FROM rag.embeddings"));
        rewriteVectors("[0,1]");
        assertNotNull(new PrecomputedEmbeddingIndex(reader(), resources()));
        assertFalse(RagIndexImporter.importIndex(importer(), resources()));
        assertEquals(before, scalar("SELECT string_agg(xmin::text, ',' ORDER BY embedding_id) FROM rag.embeddings"));
    }

    @Test void invalidImportRollsBackAndCanBeRetried() throws Exception {
        // More than one batch: rows have reached PostgreSQL before the final checksum fails.
        var hierarchy = new HSNomenclature();
        hierarchy.addChapter("01", HSVersion.V_2022, "Animals");
        var vectors = new StringBuilder();
        var texts = new ArrayList<List<String>>();
        for (int i = 1; i <= 300; i++) {
            String code = String.format(java.util.Locale.ROOT, "01%04d", i);
            String heading = code.substring(0, 4);
            if (hierarchy.getHeading(heading).isEmpty()) hierarchy.addHeading(heading, HSVersion.V_2022, "Heading " + heading);
            hierarchy.addSubHeading(code, HSVersion.V_2022, "Candidate " + code);
            texts.add(List.of(code, "Animals > Heading " + heading + " > Candidate " + code));
            vectors.append(encode(Map.of("code", code, "vector", List.of(1, 0)))).append('\n');
        }
        Files.writeString(directory.resolve("vectors.jsonl"), vectors);
        var manifest = (ObjectNode) parse(Files.readString(directory.resolve("manifest.json")));
        manifest.put("count", 300);
        ((ObjectNode) manifest.get("source")).put("descriptions_sha256", sha(encode(texts).getBytes(StandardCharsets.UTF_8)));
        // Keep the original, now invalid vectors checksum.
        Files.writeString(directory.resolve("manifest.json"), encode(manifest));
        var resources = new PrecomputedIndexResources(directory, RagTestSupport.hsCodeService(hierarchy));
        var failure = assertThrows(IllegalArgumentException.class, () -> RagIndexImporter.importIndex(importer(), resources));
        assertTrue(failure.getMessage().contains("checksum"));
        assertEquals("0", scalar("SELECT count(*) FROM rag.embeddings"));
        RagTestSupport.fixtures(directory);
        assertTrue(RagIndexImporter.importIndex(importer(), resources()));
    }

    @Test void concurrentImportPublishesOnlyOneCompleteIndex() throws Exception {
        var resources = resources();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> job = () -> { ready.countDown(); start.await(); return RagIndexImporter.importIndex(importer(), resources); };
            var first = executor.submit(job); var second = executor.submit(job);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        assertEquals("3", scalar("SELECT count(*) FROM rag.embeddings"));
    }

    @Test void exactTiesCrossingLimitUseHsCodeOrder() throws Exception {
        rewriteVectors("[1,0]");
        RagIndexImporter.importIndex(importer(), resources());
        var index = new PrecomputedEmbeddingIndex(reader(), resources());
        assertEquals(List.of("010121"), codes(index, new double[]{1, 0}, 1));
        assertEquals(List.of("010121", "010129"), codes(index, new double[]{1, 0}, 2));
    }

    @Test void openingDoesNotInspectDatabaseContents() throws Exception {
        assertNotNull(new PrecomputedEmbeddingIndex(reader(), resources()));
        RagIndexImporter.importIndex(importer(), resources());
        execute(importer(), "DELETE FROM rag.embeddings WHERE metadata->>'code' = '010121'");
        var index = new PrecomputedEmbeddingIndex(reader(), resources());
        assertEquals(2, codes(index, new double[]{1, 0}, 3).size());
        assertFalse(RagIndexImporter.importIndex(importer(), resources()));
    }

    @Test void missingTableIsNotCreatedAndImporterNeedsNoDdlPrivileges() throws Exception {
        assertEquals("false", scalar("SELECT has_schema_privilege('rag_import', 'rag', 'CREATE')::text"));
        assertTrue(RagIndexImporter.importIndex(importer(), resources()));
        execute(admin(), "DROP TABLE rag.embeddings");
        var failure = assertThrows(SQLException.class, () -> RagIndexImporter.importIndex(importer(), resources()));
        assertEquals("42P01", failure.getSQLState());
        assertNull(scalar("SELECT to_regclass('rag.embeddings')::text"));
        assertThrows(RuntimeException.class, () -> new PrecomputedEmbeddingIndex(datasource("rag_reader", "wrong"), resources()).search(new double[]{1, 0}, 1));
    }

    @Test void importsFromJarStreams() throws Exception {
        Path jar = directory.resolve("index.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("manifest.json", "vectors.jsonl")) {
                output.putNextEntry(new JarEntry(name)); output.write(Files.readAllBytes(directory.resolve(name))); output.closeEntry();
            }
        }
        try (var source = new JarFile(jar.toFile())) {
            PrecomputedIndexResources.ResourceOpener opener = name -> source.getInputStream(source.getJarEntry(name));
            var resources = new PrecomputedIndexResources(opener, RagTestSupport.hsCodeService());
            assertTrue(RagIndexImporter.importIndex(importer(), resources));
            assertEquals(List.of("010121"), codes(new PrecomputedEmbeddingIndex(reader(), resources), new double[]{1, 0}, 1));
        }
    }

    @Test @EnabledIfSystemProperty(named = "rag.fullIndex", matches = "true")
    void importsFullIndexAndMatchesJavaReference() throws Exception {
        migrateFreshSchema();
        var resources = PrecomputedIndexResources.packaged(new HSCodeServiceImpl());
        assertEquals(5612, resources.size()); assertEquals(1536, resources.dimensions);
        long start = System.nanoTime();
        assertTrue(RagIndexImporter.importIndex(importer(), resources));
        var index = RagHSCodeAnalysisService.loadIndex(reader(), new HSCodeServiceImpl());
        var reference = RagTestSupport.index(resources);
        var queries = new ArrayList<double[]>(); int[] position = {0};
        var positions = Set.of(0, resources.size() / 3, 2 * resources.size() / 3, resources.size() - 1);
        resources.readVectors((row, vector) -> {
            if (positions.contains(position[0]++)) {
                double[] query = new double[vector.length];
                for (int i = 0; i < vector.length; i++) query[i] = vector[i];
                queries.add(query);
            }
        });
        for (var query : queries) assertEquals(codes(reference, query, 20), codes(index, query, 20));
        assertFalse(RagIndexImporter.importIndex(importer(), resources));
        assertEquals("5612", scalar("SELECT count(*) FROM rag.embeddings"));
        System.out.printf("Full pgvector index: %d vectors, %d dimensions, %d queries, %.2fs%n",
                index.size(), index.dimensions(), queries.size(), (System.nanoTime() - start) / 1e9);
    }

    private void rewriteVectors(String vector) throws Exception {
        var lines = Files.readAllLines(directory.resolve("vectors.jsonl"));
        for (int i = 0; i < lines.size(); i++) {
            var row = (ObjectNode) parse(lines.get(i)); row.set("vector", parse(vector)); lines.set(i, encode(row));
        }
        Files.write(directory.resolve("vectors.jsonl"), lines);
        var manifest = (ObjectNode) parse(Files.readString(directory.resolve("manifest.json")));
        manifest.put("vectors_sha256", sha(Files.readAllBytes(directory.resolve("vectors.jsonl"))));
        Files.writeString(directory.resolve("manifest.json"), encode(manifest));
    }
}
