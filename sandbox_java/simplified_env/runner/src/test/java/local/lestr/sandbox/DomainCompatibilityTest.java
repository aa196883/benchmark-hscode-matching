package local.lestr.sandbox;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.MatchingHSCodeComparator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DomainCompatibilityTest {
    @Test void codesKeepLeadingZerosAndIndustrialFormatting() {
        assertEquals("01", HSCode.hsCode("01").toString());
        assertEquals("01.01", HSCode.hsCode("0101").toString());
        var code = HSCode.hsCode("0101.21");
        assertEquals("010121", code.toDigits());
        assertEquals("0101.21", code.toString());
        assertEquals("01", code.chapterCode());
        assertEquals("01", code.headingCode());
        assertEquals("21", code.subHeadingCode());
        assertEquals(HSCode.hsCode("010121"), code);
        assertEquals(HSCode.hsCode("010121").hashCode(), code.hashCode());
        assertThrows(IllegalArgumentException.class, () -> HSCode.hsCode("01012100"));
        assertThrows(IllegalArgumentException.class, () -> HSCode.hsCode(" 010121 "));
    }
    @Test void preserveResolveBehaviorRatherThanFixIndustrialImplementation() {
        assertEquals(HSCode.hsCode("010121"), HSCode.hsCode("01").resolve("0121"));
        assertThrows(IllegalArgumentException.class, () -> HSCode.hsCode("010121").resolve("01"));
    }
    @Test void versionsAndScoreBoundsAreUnchanged() {
        assertEquals(HSVersion.V_2022, HSVersion.fromString("2022"));
        assertThrows(IllegalArgumentException.class, () -> HSVersion.fromString("2028"));
        assertThrows(IllegalArgumentException.class, () -> new MatchingScore(-1));
        assertThrows(IllegalArgumentException.class, () -> new MatchingScore(6));
        assertTrue(new MatchingScore(5).compareTo(new MatchingScore(0)) > 0);
    }
    @Test void sortingIsDescendingAndStableOnTies() {
        var a = new MatchingHSCode(HSCode.hsCode("01"), new MatchingScore(3));
        var b = new MatchingHSCode(HSCode.hsCode("02"), new MatchingScore(5));
        var c = new MatchingHSCode(HSCode.hsCode("03"), new MatchingScore(3));
        var list = new ArrayList<>(List.of(a, b, c));
        list.sort(new MatchingHSCodeComparator());
        assertEquals(List.of(b, a, c), list);
    }
}
