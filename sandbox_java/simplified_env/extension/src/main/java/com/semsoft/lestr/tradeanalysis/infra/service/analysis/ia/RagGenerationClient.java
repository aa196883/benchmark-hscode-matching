package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.JsonNode;

/** Generates the JSON answer as text, independently of the provider protocol. */
@FunctionalInterface
public interface RagGenerationClient {
    String generate(String instructions, String input, JsonNode schema);
}
