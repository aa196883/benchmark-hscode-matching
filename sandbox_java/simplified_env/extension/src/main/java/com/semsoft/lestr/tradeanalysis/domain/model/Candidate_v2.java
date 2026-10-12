package com.semsoft.lestr.tradeanalysis.domain.model;

/** Ranked catalogue candidate; no confidence score is inferred by RAG. */
public record Candidate_v2(String code, int rank, String description, String explanation) {}
