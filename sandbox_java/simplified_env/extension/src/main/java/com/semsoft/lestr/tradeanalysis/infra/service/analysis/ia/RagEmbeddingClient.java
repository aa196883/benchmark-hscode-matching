package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

/** Vectorizes the query without rebuilding the catalogue index. */
@FunctionalInterface
public interface RagEmbeddingClient {
    double[] embed(String description);
    default Integer dimensions() { return null; }
}
