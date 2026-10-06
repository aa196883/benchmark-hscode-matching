package com.semsoft.lestr.tradeanalysis.domain.model;

import java.util.List;

public record SearchResult(Source source, List<MatchingHSCode> matchingHSCodes) {
}
