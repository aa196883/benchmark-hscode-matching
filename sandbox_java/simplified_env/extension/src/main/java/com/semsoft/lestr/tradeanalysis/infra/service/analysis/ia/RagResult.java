package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Classification result, including explanations and requests for clarification. */
public record RagResult(String status, List<Candidate> candidates,
                        @JsonProperty("missing_information") List<String> missingInformation) {
    public static final double DEFAULT_SCORE = 2.5;
    public record Candidate(String code, int rank, String description, double score, String explanation) {}
    public RagResult {
        candidates = List.copyOf(candidates);
        missingInformation = List.copyOf(missingInformation);
    }
}
