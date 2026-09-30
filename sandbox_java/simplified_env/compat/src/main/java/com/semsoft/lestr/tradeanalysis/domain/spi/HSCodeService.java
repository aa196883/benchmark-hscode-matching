package com.semsoft.lestr.tradeanalysis.domain.spi;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeWithDescription;
import com.semsoft.lestr.tradeanalysis.domain.model.HSNomenclature;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;

import java.util.Collection;
import java.util.List;

public interface HSCodeService {
    HSNomenclature getHSNomenclature();

    List<HSCodeWithDescription> getAllHsCodes();

    boolean validHSCode(HSCode hsCode, HSVersion version);

    Collection<HSCode> convertHSCode(HSCode hsCode, HSVersion fromVersion, HSVersion toVersion);
}
