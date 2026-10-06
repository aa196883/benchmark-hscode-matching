package local.lestr.sandbox;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.ResponseFormatUtils;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompletionCompatibilityTest {
    private InMemoryHSCodeService catalogue() {
        return new InMemoryHSCodeService(HSVersion.V_2022, List.of(
                new HSCodeWithDescription(HSCode.hsCode("010121"), "Breeding horses"),
                new HSCodeWithDescription(HSCode.hsCode("010129"), "Other horses")));
    }
    @Test void searchParsesConvertsFiltersAndSortsUsingSuppliedClasses() {
        var chat = new ScriptedChatService("""
                {"ignored":true,"candidates":[
                  {"hs_2022_code":"0101.29","percent_relevance_score":50,"ignored":"ok"},
                  {"hs_2022_code":"01 01 21","percent_relevance_score":96},
                  {"hs_2022_code":"999999","percent_relevance_score":100},
                  {"hs_2022_code":"invalid","percent_relevance_score":100}]}
                """, "analysis");
        var service = LocalCompletionFactory.scripted(catalogue(), HSVersion.V_2022, chat);
        var result = service.searchFromDescription("horses");
        assertEquals(Source.OpenAI_ChatGPT, result.source());
        assertEquals(List.of("010121", "010129"), result.matchingHSCodes().stream().map(c -> c.HSCode().toDigits()).toList());
        assertEquals(List.of(5, 3), result.matchingHSCodes().stream().map(c -> c.score().score()).toList());
        var request = chat.requests().getFirst();
        assertEquals("horses", request.userMessage());
        assertEquals("gpt-4.1-mini", request.modelName());
        assertTrue(request.systemMessage().contains("HS 2022"));
        assertTrue(request.systemMessage().contains("max 5 responses"));
        assertNotNull(request.responseFormat().jsonSchema());
    }
    @Test void nullAndMalformedResponsesReturnEmptyButInvalidStructureThrows() {
        for (String response : Arrays.asList(null, "not json", "{\"candidates\":[]}")) {
            var service = LocalCompletionFactory.scripted(catalogue(), HSVersion.V_2022, new ScriptedChatService(response, null));
            assertTrue(service.searchFromDescription("horses").matchingHSCodes().isEmpty());
        }
        var service = LocalCompletionFactory.scripted(catalogue(), HSVersion.V_2022, new ScriptedChatService("{}", null));
        assertThrows(NullPointerException.class, () -> service.searchFromDescription("horses"));
    }
    @Test void completionDoesNotDeduplicateOrEnforceFiveResults() {
        String candidate = "{\"hs_2022_code\":\"010121\",\"percent_relevance_score\":100}";
        var response = "{\"candidates\":[" + String.join(",", Collections.nCopies(6, candidate)) + "]}";
        var service = LocalCompletionFactory.scripted(catalogue(), HSVersion.V_2022, new ScriptedChatService(response, null));
        assertEquals(6, service.searchFromDescription("horses").matchingHSCodes().size());
    }
    @Test void analyseUsesFormattedCodeAndUnstructuredAnswer() {
        var chat = new ScriptedChatService(null, "Test explanation");
        var service = LocalCompletionFactory.scripted(catalogue(), HSVersion.V_2022, chat);
        assertEquals(new AnalyseResult(Source.OpenAI_ChatGPT, "Test explanation"), service.analyse("horses", HSCode.hsCode("010121")));
        var request = chat.requests().getFirst();
        assertEquals("How well would the description \"horses\" fit HS 2022 code 0101.21?", request.userMessage());
        assertNull(request.responseFormat());
    }
    @Test void percentageRoundingAndConversionFixturesFollowIndustrialRules() {
        var utils = new ResponseFormatUtils();
        assertEquals(0, utils.makeStars(9).score());
        assertEquals(1, utils.makeStars(10).score());
        assertEquals(5, utils.makeStars(100).score());
        assertThrows(IllegalArgumentException.class, () -> utils.makeStars(120));
        for (String code : Arrays.asList(null, "", "  ", "NULL", "999999"))
            assertTrue(utils.convert(new Candidate(code, 100), catalogue(), HSVersion.V_2022, true).isEmpty());
        assertTrue(utils.convert(new Candidate("010121", 120), catalogue(), HSVersion.V_2022, true).isEmpty());
        var old = HSCode.hsCode("010120");
        var next = HSCode.hsCode("010121");
        var service = new InMemoryHSCodeService(HSVersion.V_2022,
                Map.of(HSVersion.V_2022, catalogue().getAllHsCodes(), HSVersion.V_2017, List.of(new HSCodeWithDescription(old, "Old fixture"))),
                Map.of(new InMemoryHSCodeService.Conversion(old, HSVersion.V_2017, HSVersion.V_2022), List.of(next)));
        assertEquals(List.of(new MatchingHSCode(next, new MatchingScore(4))), utils.convert(new Candidate("010120", 80), service, HSVersion.V_2022, true));
        assertTrue(utils.convert(new Candidate("010120", 80), service, HSVersion.V_2022, false).isEmpty());
    }
}
