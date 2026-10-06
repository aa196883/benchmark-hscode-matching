package local.lestr.sandbox;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.ResponseFormatUtils;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NomenclatureCompatibilityTest {
    private HSCodeWithDescription row(String code, String text) {
        return new HSCodeWithDescription(HSCode.hsCode(code), text);
    }
    @Test void buildsHierarchyFromUnorderedRowsWithoutInventingParents() {
        var catalogue = new InMemoryHSCodeService(HSVersion.V_2022, List.of(
                row("010121", "Breeding horses"), row("0101", "Horses"), row("01", "Animals")));
        var nomenclature = catalogue.getHSNomenclature();
        var child = nomenclature.getSubHeading("010121").orElseThrow();
        assertEquals(HSCode.hsCode("010121"), child.getNomenclatureCode());
        assertEquals("21", child.getLevelCode());
        assertEquals("Horses", child.getParent().getDescription());
        assertEquals(Set.of(HSVersion.V_2022), child.getVersions());
        assertTrue(nomenclature.toString().contains("    0101.21 (2022) Breeding horses"));
        assertTrue(nomenclature.getHeading("01").isEmpty());
        assertTrue(nomenclature.getSubHeading("999999").isEmpty());
        nomenclature.addChapter("02", HSVersion.V_2022, "Fixture");
        assertTrue(catalogue.getHSNomenclature().getChapter("02").isEmpty());
    }
    @Test void mergedEditionsUseIndustrialDescriptionUpdateRules() {
        var catalogue = new InMemoryHSCodeService(HSVersion.V_2022,
                Map.of(HSVersion.V_2017, List.of(row("01", "Old")),
                        HSVersion.V_2022, List.of(row("01", "New"))), Map.of());
        var level = catalogue.getHSNomenclature().getChapter("01").orElseThrow();
        assertEquals("New", level.getDescription());
        assertEquals(Set.of(HSVersion.V_2017, HSVersion.V_2022), level.getVersions());
        level.updateWithVersion(HSVersion.V_2022, "Same edition ignored");
        assertEquals("New", level.getDescription());
        var recent = new HSLevel("01", HSVersion.V_2022, "Recent");
        recent.updateWithVersion(HSVersion.V_2017, "Older");
        assertEquals("Recent", recent.getDescription());
    }
    @Test void hierarchyValidationAndReplacementMatchReceivedClasses() {
        var nomenclature = new HSNomenclature();
        assertThrows(IllegalArgumentException.class, () -> nomenclature.addChapter("001", HSVersion.V_2022, "x"));
        assertThrows(IllegalArgumentException.class, () -> new HSLevel("aa", HSVersion.V_2022, "x"));
        assertThrows(NoSuchElementException.class, () -> nomenclature.addHeading("0101", HSVersion.V_2022, "x"));
        var chapter = nomenclature.addChapter("01", HSVersion.V_2022, "x");
        var child = chapter.addChildLevel("01", HSVersion.V_2022, "first");
        chapter.addChildLevel("01", HSVersion.V_2022, "replacement");
        assertEquals("replacement", chapter.getChild("01").getDescription());
        assertEquals(child, chapter.getChild("01"));
    }
    @Test void numberCandidatesParsingIsRestored() {
        var utils = new ResponseFormatUtils();
        var result = utils.parseResponseNumberCandidates("""
                {"numberCandidates":[{"number":2,"percent_relevance_score":85}],"ignored":true}
                """);
        assertEquals(new NumberCandidates(List.of(new NumberCandidate(2, 85))), result.orElseThrow());
        assertTrue(utils.parseResponseNumberCandidates("invalid").isEmpty());
    }
}
