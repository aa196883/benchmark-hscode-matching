package com.semsoft.lestr.tradeanalysis.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** Complete RAG result shared by all public search entry points. */
public record SearchResult_v2(Source source, Status status, List<Candidate_v2> candidates,
                              @JsonProperty("missing_information") List<String> missingInformation) {
    public enum Status {
        @JsonProperty("ok") OK,
        @JsonProperty("needs_info") NEEDS_INFO
    }
    public SearchResult_v2 {
        Objects.requireNonNull(source);
        Objects.requireNonNull(status);
        candidates = List.copyOf(candidates);
        missingInformation = List.copyOf(missingInformation);
        if (candidates.isEmpty() || missingInformation.stream().anyMatch(String::isBlank)
                || (status == Status.OK ? !missingInformation.isEmpty() : missingInformation.isEmpty()))
            throw new IllegalArgumentException("Inconsistent RAG result");
    }
}
