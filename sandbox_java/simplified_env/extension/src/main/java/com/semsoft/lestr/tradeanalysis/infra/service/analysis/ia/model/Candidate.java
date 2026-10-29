package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model;

import org.jspecify.annotations.Nullable;

/** Ranked catalogue candidate; RAG supplies an independent 1-to-5 confidence rating. */
public record Candidate(String code, int rank, String description,
                        @Nullable String explanation, @Nullable Double rating) {
    public Candidate {
        if (rating != null && (!Double.isFinite(rating) || rating < 1 || rating > 5))
            throw new IllegalArgumentException("Rating must be a finite number between 1 and 5");
    }
    /** Existing non-RAG callers have no model confidence assessment. */
    public Candidate(String code, int rank, String description, @Nullable String explanation) {
        this(code, rank, description, explanation, null);
    }
}
