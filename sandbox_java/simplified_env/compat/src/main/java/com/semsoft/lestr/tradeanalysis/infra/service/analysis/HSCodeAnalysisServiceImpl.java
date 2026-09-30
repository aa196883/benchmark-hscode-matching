package com.semsoft.lestr.tradeanalysis.infra.service.analysis;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.AnalyseResult;
import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeAnalysisException;
import com.semsoft.lestr.tradeanalysis.domain.model.SearchResult;
import com.semsoft.lestr.tradeanalysis.domain.model.Source;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class HSCodeAnalysisServiceImpl implements HSCodeAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(HSCodeAnalysisServiceImpl.class);

    private final List<HSCodeAnalysisService> hsCodeServiceList;

    public HSCodeAnalysisServiceImpl(List<HSCodeAnalysisService> hsCodeServiceList) {
        this.hsCodeServiceList = hsCodeServiceList;
    }

    @Override
    public SearchResult searchFromDescription(String description) throws HSCodeAnalysisException {
        for (HSCodeAnalysisService hsCodeService : hsCodeServiceList) {
            try {
                SearchResult searchResult = hsCodeService.searchFromDescription(description);
                log.debug("Result for {} on {} : {}", description, hsCodeService.getClass().getName(), searchResult);
                return searchResult;
            } catch (Throwable t) {
                log.warn("Error for searchFromDescription when calling {} : {}", hsCodeService.getClass().getName(), t.getMessage(), t);
            }
        }
        throw new RuntimeException("No HSCodeAnalysisService can answer");
    }

    @Override
    public AnalyseResult analyse(String description, HSCode hsCode) throws HSCodeAnalysisException {
        for (HSCodeAnalysisService hsCodeService : hsCodeServiceList) {
            try {
                return hsCodeService.analyse(description, hsCode);
            } catch (Throwable t) {
                log.warn("Error for analyse when calling {} : {}", hsCodeService.getClass().getName(), t.getMessage(), t);
            }
        }
        throw new RuntimeException("No HSCodeAnalysisService can answer");
    }

    @Override
    public Source getSource() {
        throw new RuntimeException("No source for this HSCodeAnalysisService");
    }
}
