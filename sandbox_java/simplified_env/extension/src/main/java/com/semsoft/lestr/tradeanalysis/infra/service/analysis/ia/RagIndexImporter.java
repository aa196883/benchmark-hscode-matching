package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.pgvector.PGvector;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** Explicit, atomic, immutable import. Requires a prepared database and the schema-owner connection. */
public final class RagIndexImporter {
    private static final long IMPORT_LOCK = 0x4853524147494e44L;
    private RagIndexImporter() {}

    /** @return true for a new import, false for an already identical and complete index. */
    public static boolean importIndex(DataSource datasource, PrecomputedIndexResources resources)
            throws IOException, SQLException {
        try (var connection = datasource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, IMPORT_LOCK);
                    lock.execute();
                }
                boolean embeddings, manifest;
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT to_regclass('rag.embeddings'), to_regclass('rag.index_manifest')")) {
                    result.next();
                    embeddings = result.getString(1) != null;
                    manifest = result.getString(2) != null;
                }
                if (embeddings || manifest) {
                    require(embeddings && manifest, "Incomplete RAG database; manual repair required");
                    verify(connection, resources);
                    resources.readVectors((row, vector) -> {});
                    connection.commit();
                    return false;
                }
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE rag.embeddings (embedding_id UUID PRIMARY KEY, embedding vector("
                            + resources.dimensions + ") NOT NULL, text TEXT NOT NULL, metadata JSONB NOT NULL)");
                    statement.execute("CREATE UNIQUE INDEX rag_embeddings_code ON rag.embeddings ((metadata->>'code'))");
                    statement.execute("CREATE TABLE rag.index_manifest (singleton BOOLEAN PRIMARY KEY CHECK (singleton), "
                            + "format_version INTEGER NOT NULL, manifest JSONB NOT NULL)");
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO rag.embeddings (embedding_id, embedding, text, metadata) VALUES (?, ?, ?, ?::jsonb)")) {
                    int[] batchSize = {0};
                    resources.readVectors((row, vector) -> {
                        insert.setObject(1, id(row.code()));
                        insert.setObject(2, new PGvector(vector));
                        insert.setString(3, row.contextualDescription());
                        insert.setString(4, encode(Map.of("code", row.code())));
                        insert.addBatch();
                        if (++batchSize[0] % 256 == 0) insert.executeBatch();
                    });
                    insert.executeBatch();
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO rag.index_manifest VALUES (true, 1, ?::jsonb)")) {
                    insert.setString(1, encode(resources.manifest));
                    insert.executeUpdate();
                }
                verify(connection, resources);
                connection.commit();
                return true;
            } catch (IOException | SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    static UUID id(String code) {
        return UUID.nameUUIDFromBytes(("rag:h6_2022:" + code).getBytes(StandardCharsets.UTF_8));
    }

    /** SELECT-only consistency checks, also used before opening the runtime store. */
    static void verify(Connection connection, PrecomputedIndexResources resources) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT format_version, manifest FROM rag.index_manifest")) {
            require(result.next(), "RAG database has no completed import");
            require(result.getInt(1) == 1 && resources.manifest.equals(parse(result.getString(2))),
                    "RAG database index differs from the packaged manifest; replacement is not automatic");
            require(!result.next(), "Multiple RAG manifests");
        }
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                        + "WHERE attrelid = 'rag.embeddings'::regclass AND attname = 'embedding' AND NOT attisdropped")) {
            require(result.next() && ("vector(" + resources.dimensions + ")").equals(result.getString(1)),
                    "RAG database vector dimensions differ from the manifest");
        }
        var expected = new HashMap<String, RagCatalog.Row>();
        for (var row : resources.rows) expected.put(row.code(), row);
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT embedding_id, text, metadata->>'code', embedding IS NOT NULL FROM rag.embeddings")) {
            while (result.next()) {
                String code = result.getString(3);
                var row = expected.remove(code);
                require(row != null && id(code).equals(result.getObject(1, UUID.class))
                                && row.contextualDescription().equals(result.getString(2)) && result.getBoolean(4),
                        "Invalid, duplicate or unknown RAG database candidate");
            }
        }
        require(expected.isEmpty(), "Incomplete RAG database index");
    }
}
