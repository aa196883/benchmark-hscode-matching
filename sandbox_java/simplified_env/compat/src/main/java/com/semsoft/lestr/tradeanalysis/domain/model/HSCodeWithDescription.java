package com.semsoft.lestr.tradeanalysis.domain.model;

import com.semsoft.lestr.shared.kernel.goods.HSCode;

import java.io.Serializable;

public record HSCodeWithDescription(HSCode hsCode, String description) implements Serializable {
}
