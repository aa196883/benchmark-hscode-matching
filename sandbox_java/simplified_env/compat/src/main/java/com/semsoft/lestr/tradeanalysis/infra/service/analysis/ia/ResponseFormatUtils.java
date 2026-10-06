package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import com.semsoft.lestr.tradeanalysis.domain.model.MatchingHSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.MatchingScore;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidate;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidates;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.NumberCandidates;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class ResponseFormatUtils {
    private static final Logger log = LoggerFactory.getLogger(ResponseFormatUtils.class);

    private final ObjectMapper objectMapper = initObjectMapper();

    private ObjectMapper initObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    public Optional<Candidates> parseResponseCandidates(String txt) {
        try {
            return Optional.of(objectMapper.readValue(txt, Candidates.class));
        } catch (JsonProcessingException e) {
            log.error("JsonProcessingException", e);
        }
        return Optional.empty();
    }

    public Optional<NumberCandidates> parseResponseNumberCandidates(String txt) {
        try {
            return Optional.of(objectMapper.readValue(txt, NumberCandidates.class));
        } catch (JsonProcessingException e) {
            log.warn("JsonProcessingException", e);
        }
        return Optional.empty();
    }

    public List<MatchingHSCode> convert(Candidate candidate, HSCodeService hsCodeService, HSVersion hsVersion, boolean hsCodeConversion) {
        if (StringUtils.isBlank(candidate.hs_2022_code()) || candidate.hs_2022_code().equalsIgnoreCase("null")) {
            return List.of();
        }
        String code = cleanHSCode(candidate.hs_2022_code());
        try {
            HSCode hsCode = HSCode.hsCode(code);
            if (hsCodeService.validHSCode(hsCode, hsVersion)) {
                return List.of(new MatchingHSCode(hsCode, makeStars(candidate.percent_relevance_score())));
            } else {
                if (hsCodeConversion && hsVersion == HSVersion.V_2022 && hsCodeService.validHSCode(hsCode, HSVersion.V_2017)) {
                    return hsCodeService.convertHSCode(hsCode, HSVersion.V_2017, HSVersion.V_2022)
                            .stream()
                            .map(hc -> new MatchingHSCode(hc, makeStars(candidate.percent_relevance_score())))
                            .toList();
                }
                log.info("Invalid hsCode {} (version {})", hsCode, hsVersion);
                return List.of();
            }
        } catch (IllegalArgumentException e) {
            log.warn("Inconsistent HSCode {}", e.getMessage(), e);
        }
        return List.of();
    }

    private String cleanHSCode(String s) {
        // Remove . et autre joyeusetées ...
        return s.replaceAll("\\.", "").replaceAll("\s+", "");
    }

    public MatchingScore makeStars(Integer score) {
        return new MatchingScore((int) Math.round(score / 20d));
    }
}
