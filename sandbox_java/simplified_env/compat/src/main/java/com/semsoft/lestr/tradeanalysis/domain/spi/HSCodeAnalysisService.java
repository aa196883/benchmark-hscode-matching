package com.semsoft.lestr.tradeanalysis.domain.spi;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.AnalyseResult;
import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeAnalysisException;
import com.semsoft.lestr.tradeanalysis.domain.model.SearchResult;
import com.semsoft.lestr.tradeanalysis.domain.model.Source;

public interface HSCodeAnalysisService {
    int NB_MAX_RESULTS = 5;
    SearchResult searchFromDescription(String description) throws HSCodeAnalysisException;

    AnalyseResult analyse(String description, HSCode hsCode) throws HSCodeAnalysisException;

    Source getSource();
}
