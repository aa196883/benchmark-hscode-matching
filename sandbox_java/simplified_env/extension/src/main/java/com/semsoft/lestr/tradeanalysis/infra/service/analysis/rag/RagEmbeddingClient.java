package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.fasterxml.jackson.databind.JsonNode;

/** Injectable query vectorizer. No catalogue indexing is performed at inference. */
public interface RagEmbeddingClient {
    record Response(double[] vector, String model, JsonNode usage) {}
    Response embed(String description);
    default String model() { return PrecomputedEmbeddingIndex.MODEL; }
    default Integer dimensions() { return null; }
}
