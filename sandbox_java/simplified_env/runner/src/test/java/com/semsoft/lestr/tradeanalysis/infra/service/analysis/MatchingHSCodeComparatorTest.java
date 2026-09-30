package com.semsoft.lestr.tradeanalysis.infra.service.analysis;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.MatchingHSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.MatchingScore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class MatchingHSCodeComparatorTest {
    private final MatchingHSCodeComparator matchingHSCodeComparator = new MatchingHSCodeComparator();

    @Test
    void compare() {
        List<MatchingHSCode> matchingHSCodes = new ArrayList<>(List.of(
                new MatchingHSCode(HSCode.hsCode("00"), new MatchingScore(0)),
                new MatchingHSCode(HSCode.hsCode("05"), new MatchingScore(5)),
                new MatchingHSCode(HSCode.hsCode("01"), new MatchingScore(1)),
                new MatchingHSCode(HSCode.hsCode("02"), new MatchingScore(2)),
                new MatchingHSCode(HSCode.hsCode("04"), new MatchingScore(4)),
                new MatchingHSCode(HSCode.hsCode("03"), new MatchingScore(3))
        ));

        matchingHSCodes.sort(matchingHSCodeComparator);
        Assertions.assertEquals(List.of(
                new MatchingHSCode(HSCode.hsCode("05"), new MatchingScore(5)),
                new MatchingHSCode(HSCode.hsCode("04"), new MatchingScore(4)),
                new MatchingHSCode(HSCode.hsCode("03"), new MatchingScore(3)),
                new MatchingHSCode(HSCode.hsCode("02"), new MatchingScore(2)),
                new MatchingHSCode(HSCode.hsCode("01"), new MatchingScore(1)),
                new MatchingHSCode(HSCode.hsCode("00"), new MatchingScore(0))
        ), matchingHSCodes);
    }
}
