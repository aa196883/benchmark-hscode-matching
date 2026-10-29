package com.semsoft.lestr.tradeanalysis.infra.service.analysis.verbatim;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeAnalysisService;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.CandidateComparator;
import com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.model.Candidate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VerbatimHSCodeServiceImpl implements HSCodeAnalysisService {

    // La liste des stop words est extraite de la définition des hscodes en prenant les termes les plus redondants ...
    private final List<String> stopWords = List.of(
            "or", "of", "and", "not", "other", "in", "than", "for", "no", "the", "with", "by", "more",
            "less", "but", "to", "their", "those", "non", "used", "made", "up", "like", "on", "from"
    );

    private final StringUtils stringUtils = new StringUtils();
    private final HSCodeService hsCodeService;
    private final Map<HSCode, String> hsCodes;

    public VerbatimHSCodeServiceImpl(HSCodeService hsCodeService, HSVersion version) {
        this.hsCodeService = hsCodeService;
        this.hsCodes = loadHScodes(version);
    }

    @Override
    public SearchResult searchFromDescription(String description) throws HSCodeAnalysisException {
        List<String> tokens = removeStopWords(stringUtils.tokenize(description));


        return new SearchResult(
                getSource(),
                SearchResult.Status.OK,
                hsCodes.entrySet().stream()
                        .map(hsCode -> searchInDescription(tokens, hsCode))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .sorted(new CandidateComparator())
                        .toList(),
                List.of());
    }

    private Map<HSCode, String> loadHScodes(HSVersion version) {
        Map<HSCode, String> result = new HashMap<>();

        for (HSLevel chapter : hsCodeService.getHSNomenclature().getChapters()) {
            if (chapter.getVersions().contains(version)) {
                result.put(chapter.getNomenclatureCode(), chapter.getDescription());
            }
            for (HSLevel heading : chapter.getChildren().values()) {
                if (heading.getVersions().contains(version)) {
                    result.put(heading.getNomenclatureCode(), heading.getDescription());
                }
                for (HSLevel subHeading : heading.getChildren().values()) {
                    if (subHeading.getVersions().contains(version)) {
                        result.put(subHeading.getNomenclatureCode(), subHeading.getDescription());
                    }
                }
            }
        }

        return result;
    }

    private List<String> removeStopWords(List<String> tokenize) {
        return tokenize.stream().filter(t -> !stopWords.contains(t)).toList();
    }

    private Optional<Candidate> searchInDescription(List<String> tokens, Map.Entry<HSCode, String> hsCode) {
        List<String> hsCodeTokens = removeStopWords(stringUtils.tokenize(hsCode.getValue()));

        int nbCommonWord = 0;
        for (String token : tokens) {
            if (hsCodeTokens.contains(token)) {
                nbCommonWord++;
            }
        }

        if (nbCommonWord >= 1) {
            return Optional.of(new Candidate(hsCode.getKey().toDigits(), score(nbCommonWord, hsCode.getKey()), hsCodes.get(hsCode.getKey()), null));
        }
        return Optional.empty();
    }

    private int score(int nbCommonWord, HSCode code) {
        double ratio;
        if (code.subHeadingCode() != null) {
            ratio = 1.1d;
        } else if (code.headingCode() != null) {
            ratio = 1.05d;
        } else {
            ratio = 1d;
        }
        return Math.min(5, (int) Math.round(nbCommonWord * ratio / 0.7d));
    }

    @Override
    public Source getSource() {
        return Source.Verbatim;
    }
}
