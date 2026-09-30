package com.semsoft.lestr.tradeanalysis.infra.service.analysis;


import com.semsoft.lestr.tradeanalysis.domain.model.MatchingHSCode;

import java.util.Comparator;

public class MatchingHSCodeComparator implements Comparator<MatchingHSCode> {
    @Override
    public int compare(MatchingHSCode t0, MatchingHSCode t1) {
        return Integer.compare(t1.score().score(), t0.score().score());
    }
}
