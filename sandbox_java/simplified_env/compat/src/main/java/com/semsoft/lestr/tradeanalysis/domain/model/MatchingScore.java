package com.semsoft.lestr.tradeanalysis.domain.model;

public record MatchingScore(int score) implements Comparable<MatchingScore> {
    public MatchingScore {
        if (score < 0 || score > 5) {
            throw new IllegalArgumentException("Score must be between 0 and 5");
        }
    }

    @Override
    public int compareTo(MatchingScore matchingScore) {
        return Integer.compare(this.score, matchingScore.score);
    }
}
