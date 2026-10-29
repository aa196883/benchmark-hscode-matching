package com.semsoft.lestr.tradeanalysis.domain.spi;

import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeAnalysisException;
import com.semsoft.lestr.tradeanalysis.domain.model.SearchResult;
import com.semsoft.lestr.tradeanalysis.domain.model.Source;

/** Search-only contract for RAG; independent of the legacy analysis chain. */
public interface HSCodeAnalysisService {
    int NB_MAX_RESULTS = 5;
    SearchResult searchFromDescription(String description) throws HSCodeAnalysisException;
    Source getSource();
}
