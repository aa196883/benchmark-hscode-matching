package com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag;

import com.fasterxml.jackson.databind.JsonNode;

/** Returns the complete Responses API envelope, including refusals and completion status. */
@FunctionalInterface
public interface RagGenerationClient {
    JsonNode generate(String instructions, String input, JsonNode schema);
    default String provider() { return "openai"; }
    default JsonNode configuration() { return RagJson.MAPPER.createObjectNode(); }
}
