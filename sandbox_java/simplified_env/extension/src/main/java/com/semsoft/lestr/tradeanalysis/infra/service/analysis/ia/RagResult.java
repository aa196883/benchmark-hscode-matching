package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Full result: explanations/questions/status remain available independently of SearchResult. */
public record RagResult(String status, List<Candidate> candidates,
                        @JsonProperty("missing_information") List<String> missingInformation,
                        JsonNode error, JsonNode metadata) {
    public static final double DEFAULT_SCORE = 2.5;
    public record Candidate(String code, int rank, String description, double score,
                            @JsonProperty("score_type") String scoreType,
                            String explanation, List<String> references) {
        public Candidate { references = List.copyOf(references); }
    }
    public RagResult {
        candidates = List.copyOf(candidates);
        missingInformation = List.copyOf(missingInformation);
        error = error == null ? null : error.deepCopy();
        metadata = metadata.deepCopy();
    }
}
