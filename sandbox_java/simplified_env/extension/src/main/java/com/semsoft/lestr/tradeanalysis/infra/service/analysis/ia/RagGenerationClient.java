package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Distinguishes an explicit provider refusal from generated JSON and failures. */
@FunctionalInterface
public interface RagGenerationClient {
    Result generate(String instructions, String input, JsonNode schema);

    sealed interface Result permits Answer, Refusal {}
    record Answer(String text) implements Result {
        public Answer { Objects.requireNonNull(text); }
    }
    record Refusal() implements Result {}
}
