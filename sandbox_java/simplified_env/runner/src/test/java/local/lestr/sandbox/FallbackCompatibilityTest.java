package local.lestr.sandbox;

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
            public SearchResult searchFromDescription(String description) { action.run(); return new SearchResult(getSource(), SearchResult.Status.NEEDS_INFO, List.of(), List.of("Intended use?")); }
            public Source getSource() { return Source.Verbatim; }
        };
    }
    @Test void emptyAnswerStopsFallback() {
        var calls = new AtomicInteger();
        var chain = new HSCodeAnalysisServiceImpl(List.of(service(() -> {}), service(calls::incrementAndGet)));
        assertTrue(chain.searchFromDescription("x").candidates().isEmpty());
        assertEquals(0, calls.get());
    }
    @Test void exceptionsAndErrorsTriggerSearchFallback() {
        var calls = new AtomicInteger();
        var chain = new HSCodeAnalysisServiceImpl(List.of(service(() -> { throw new AssertionError("fixture"); }), service(calls::incrementAndGet)));
        assertEquals(Source.Verbatim, chain.searchFromDescription("x").source());
        assertEquals(1, calls.get());
    }
    @Test void exhaustedAndEmptyChainsAndSourceFail() {
        var chain = new HSCodeAnalysisServiceImpl(List.of(service(() -> { throw new HSCodeAnalysisException("fixture"); })));
        assertEquals("No HSCodeAnalysisService can answer", assertThrows(RuntimeException.class, () -> chain.searchFromDescription("x")).getMessage());
        assertThrows(RuntimeException.class, chain::getSource);
        assertThrows(RuntimeException.class, () -> new HSCodeAnalysisServiceImpl(List.of()).searchFromDescription("x"));
    }
}
