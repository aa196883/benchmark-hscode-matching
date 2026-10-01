package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.fasterxml.jackson.databind.JsonNode;

/** Sanitized provider failure: never stores request headers, API key or response error body. */
public final class RagProviderException extends RuntimeException {
    private final JsonNode details;
    public RagProviderException(String kind, String message, Integer statusCode, String requestId) {
        super(message);
        var node = RagJson.MAPPER.createObjectNode().put("kind", kind).put("message", message);
        if (statusCode == null) node.putNull("status_code"); else node.put("status_code", statusCode);
        if (requestId == null) node.putNull("request_id"); else node.put("request_id", requestId);
        details = node;
    }
    public JsonNode details() { return details.deepCopy(); }
}
