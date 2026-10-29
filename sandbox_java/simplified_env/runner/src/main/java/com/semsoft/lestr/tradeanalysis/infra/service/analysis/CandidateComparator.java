package com.semsoft.lestr.tradeanalysis.infra.service.analysis;


import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidate;

import java.util.Comparator;

public class CandidateComparator implements Comparator<Candidate> {
    @Override
    public int compare(Candidate t0, Candidate t1) {
        return Integer.compare(t1.rank(), t0.rank());
    }
}
