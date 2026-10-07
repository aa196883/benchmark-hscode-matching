package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.require;

/** Read-only PostgreSQL index. Opening never imports vectors or creates database objects. */
public final class PrecomputedEmbeddingIndex {
    public static final String MODEL = PrecomputedIndexResources.MODEL;
    private final RagCatalog catalog;
    private final EmbeddingStore<TextSegment> store;
    private final int dimensions;
    private final Integer configuredDimensions;
    private final int size;

    public PrecomputedEmbeddingIndex(DataSource datasource, PrecomputedIndexResources resources) throws SQLException {
        this(resources, openStore(datasource, resources));
    }
    // Test seam: production always opens PgVectorEmbeddingStore through the public constructor.
    PrecomputedEmbeddingIndex(PrecomputedIndexResources resources, EmbeddingStore<TextSegment> store) {
        this.catalog = resources.catalog;
        this.dimensions = resources.dimensions;
        this.configuredDimensions = resources.configuredDimensions;
        this.size = resources.size();
        this.store = Objects.requireNonNull(store);
    }
    private static PgVectorEmbeddingStore openStore(DataSource datasource, PrecomputedIndexResources resources)
            throws SQLException {
        try (var connection = datasource.getConnection()) {
            RagIndexImporter.verify(connection, resources);
        }
        return PgVectorEmbeddingStore.datasourceBuilder().datasource(datasource)
                .table("rag.embeddings").dimension(resources.dimensions)
                .createTable(false).dropTableFirst(false).useIndex(false)
                .skipCreateVectorExtension(true).build();
    }
    public List<RagCatalog.Row> search(double[] vector, int topK) {
        require(topK > 0, "topK must be positive");
        double norm = PrecomputedIndexResources.validate(vector, dimensions);
        float[] query = new float[dimensions];
        for (int j = 0; j < dimensions; j++) query[j] = (float) (vector[j] / norm);
        int wanted = Math.min(topK, size);
        int limit = Math.min(wanted + 1, size);
        List<EmbeddingMatch<TextSegment>> matches;
        while (true) {
            matches = store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(query))
                    .maxResults(limit).minScore(0.0).build()).matches().stream()
                    .sorted(Comparator.<EmbeddingMatch<TextSegment>>comparingDouble(EmbeddingMatch::score)
                            .reversed().thenComparing(match -> code(match.embedded())))
                    .toList();
            // Include the entire tie group crossing K, without transferring the full index normally.
            if (limit == size || matches.size() < limit || matches.size() <= wanted
                    || Double.compare(matches.get(wanted - 1).score(), matches.getLast().score()) != 0) break;
            limit = Math.min(size, limit * 2);
        }
        return matches.stream().limit(wanted).map(match -> {
            var row = catalog.row(code(match.embedded()));
            require(row != null && row.eligible(), "Unknown database candidate");
            return row;
        }).toList();
    }
    private static String code(TextSegment segment) {
        require(segment != null, "Missing database segment");
        String code = segment.metadata().getString("code");
        require(code != null, "Missing database HS code");
        return code;
    }
    public int dimensions() { return dimensions; }
    public Integer configuredDimensions() { return configuredDimensions; }
    public RagCatalog catalog() { return catalog; }
    public int size() { return size; }
}
