package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.pgvector.PGvector;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** Explicit, atomic import for local data preparation into a prepared database.
 * Requires an existing rag.embeddings table and SELECT/INSERT privileges only.
 */
public final class RagIndexImporter {
    private static final long IMPORT_LOCK = 0x4853524147494e44L;
    private RagIndexImporter() {}

    /** @return true for a new import, false when the table already contains data (no comparison). */
    public static boolean importIndex(DataSource datasource, PrecomputedIndexResources resources)
            throws IOException, SQLException {
        try (var connection = datasource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, IMPORT_LOCK);
                    lock.execute();
                }
                // The schema is provisioned beforehand (Flyway V1 in the manual test).
                // A missing table is an SQL error: this class never creates database objects.
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM rag.embeddings)")) {
                    result.next();
                    if (result.getBoolean(1)) {
                        connection.commit();
                        return false;
                    }
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO rag.embeddings (embedding_id, embedding, text, metadata) VALUES (?, ?, ?, ?::jsonb)")) {
                    int[] batchSize = {0};
                    resources.readVectors((row, vector) -> {
                        insert.setObject(1, id(row.hsCode().toDigits()));
                        insert.setObject(2, new PGvector(vector));
                        insert.setString(3, resources.contextualDescription(row));
                        insert.setString(4, encode(Map.of("code", row.hsCode().toDigits())));
                        insert.addBatch();
                        if (++batchSize[0] % 256 == 0) insert.executeBatch();
                    });
                    insert.executeBatch();
                }
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

}
