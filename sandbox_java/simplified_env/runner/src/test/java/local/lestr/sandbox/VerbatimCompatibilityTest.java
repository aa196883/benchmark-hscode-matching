package local.lestr.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.HSCodeAnalysisServiceImpl;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.verbatim.VerbatimHSCodeServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VerbatimCompatibilityTest {
    @Test void receivedVerbatimCallerKeepsItsConstructorAndDoesNotInventRatings() {
        var catalogue = new InMemoryHSCodeService(HSVersion.V_2022, List.of(
                new HSCodeWithDescription(HSCode.hsCode("01"), "Animals"),
                new HSCodeWithDescription(HSCode.hsCode("0101"), "Horses"),
                new HSCodeWithDescription(HSCode.hsCode("010121"), "Breeding horses")));
        var verbatim = new VerbatimHSCodeServiceImpl(catalogue, HSVersion.V_2022);
        var chain = new HSCodeAnalysisServiceImpl(List.of(verbatim));
        var result = chain.searchFromDescription("breeding horses");
        assertEquals(Source.Verbatim, result.source());
        assertTrue(result.candidates().stream().anyMatch(c -> c.code().equals("010121")));
        assertTrue(result.candidates().stream().allMatch(c -> c.rating() == null));
        var json = new ObjectMapper().valueToTree(result);
        assertTrue(json.path("candidates").get(0).path("rating").isNull());
    }
}
