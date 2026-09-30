package local.lestr.sandbox;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.HSCodeAnalysisServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FallbackCompatibilityTest {
    private HSCodeAnalysisService service(Runnable action) {
        return new HSCodeAnalysisService() {
            public SearchResult searchFromDescription(String description) { action.run(); return new SearchResult(getSource(), List.of()); }
            public AnalyseResult analyse(String description, HSCode code) { action.run(); return new AnalyseResult(getSource(), "ok"); }
            public Source getSource() { return Source.Verbatim; }
        };
    }
    @Test void emptyAnswerStopsFallback() {
        var calls = new AtomicInteger();
        var chain = new HSCodeAnalysisServiceImpl(List.of(service(() -> {}), service(calls::incrementAndGet)));
        assertTrue(chain.searchFromDescription("x").matchingHSCodes().isEmpty());
        assertEquals(0, calls.get());
    }
    @Test void exceptionsAndErrorsTriggerFallbackForBothOperations() {
        var calls = new AtomicInteger();
        var chain = new HSCodeAnalysisServiceImpl(List.of(service(() -> { throw new AssertionError("fixture"); }), service(calls::incrementAndGet)));
        assertEquals(Source.Verbatim, chain.searchFromDescription("x").source());
        assertEquals("ok", chain.analyse("x", HSCode.hsCode("01")).analyse());
        assertEquals(2, calls.get());
    }
    @Test void exhaustedAndEmptyChainsAndSourceFail() {
        var chain = new HSCodeAnalysisServiceImpl(List.of(service(() -> { throw new HSCodeAnalysisException("fixture"); })));
        assertEquals("No HSCodeAnalysisService can answer", assertThrows(RuntimeException.class, () -> chain.searchFromDescription("x")).getMessage());
        assertThrows(RuntimeException.class, () -> chain.analyse("x", HSCode.hsCode("01")));
        assertThrows(RuntimeException.class, chain::getSource);
        assertThrows(RuntimeException.class, () -> new HSCodeAnalysisServiceImpl(List.of()).searchFromDescription("x"));
    }
}
