package com.semsoft.lestr.tradeanalysis.infra.service;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeWithDescription;
import com.semsoft.lestr.tradeanalysis.domain.model.HSLevel;
import com.semsoft.lestr.tradeanalysis.domain.model.HSNomenclature;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import jakarta.json.Json;
import jakarta.json.stream.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class HSCodeServiceImpl implements HSCodeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HSCodeServiceImpl.class);

    private static final String HS_JSON_RES_LOCATION_PATTERN = "/com/semsoft/lestr/tradeanalysis/infra/service/hs_references/*.json";

    private final HSNomenclature hsNomenclature;
    private final Multimap<HSCode, HSCode> conversionsFrom2017to2022;

    public HSCodeServiceImpl() throws IOException {
        this.hsNomenclature = loadHsNomenclature();
        this.conversionsFrom2017to2022 = loadConversions();
    }

    private Multimap<HSCode, HSCode> loadConversions() {
        Multimap<HSCode, HSCode> conversions = ArrayListMultimap.create();

        try (InputStream resourceAsStream = this.getClass().getResourceAsStream("/com/semsoft/lestr/tradeanalysis/infra/service/hs_references/conversionHS2022-HS2017.csv");
             BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(resourceAsStream)))) {

            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                } else {
                    String[] split = line.split(",");
                    if (split.length == 2) {
                        HSCode hsCode2022 = HSCode.hsCode(split[0]);
                        HSCode hsCode2017 = HSCode.hsCode(split[1]);
                        conversions.put(hsCode2017, hsCode2022);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return conversions;
    }

    private static HSNomenclature loadHsNomenclature() throws IOException {
        HSNomenclature hsNomenclature = new HSNomenclature();
        Resource[] pathMatchingResources = new PathMatchingResourcePatternResolver().getResources(HS_JSON_RES_LOCATION_PATTERN);
        for (Resource resource : pathMatchingResources) {
            loadHSCodeNomenclature(hsNomenclature, resource);
        }
        return hsNomenclature;
    }

    private static void loadHSCodeNomenclature(HSNomenclature nomenclature, Resource resource) throws IOException {
        try (InputStream resourceAsStream = resource.getInputStream()) {
            HSVersion version = null;
            try (JsonParser parser = Json.createParser(resourceAsStream)) {
                boolean inResults = false;
                boolean inResult = false;
                while (parser.hasNext()) {
                    JsonParser.Event event = parser.next();
                    if (event == JsonParser.Event.KEY_NAME) {
                        String keyName = parser.getString();
                        if (keyName.equals("results") && !inResults) {
                            inResults = true;
                        } else if (inResult) {
                            loadHSCodeLevelFromResultsItem(parser, keyName, nomenclature, version);
                        } else if (keyName.equals("className")) {
                            version = extractHSVersionFromClassName(parser);
                        }
                    }
                    if (event == JsonParser.Event.START_OBJECT && inResults) {
                        inResult = true;
                    }
                    if (event == JsonParser.Event.END_OBJECT) {
                        if (inResult) {
                            inResult = false;
                        } else if (inResults) {
                            inResults = false;
                        }
                    }
                }
            }
        }
    }

    private static HSVersion extractHSVersionFromClassName(JsonParser parser) {
        parser.next();
        String className = parser.getString();
        return HSVersion.fromString(className.substring(2, 6));
    }

    private static void loadHSCodeLevelFromResultsItem(JsonParser parser, String keyName, HSNomenclature nomenclature, HSVersion version) {
        parser.next();
        String levelCode = parser.getString();
        if (keyName.equals("id")) {
            parser.next(); // text key
            parser.next(); // text value
            String text = parser.getString().replace(levelCode + " - ", "");

            try {
                if (levelCode.length() == 2) {
                    Optional<HSLevel> chapter = nomenclature.getChapter(levelCode);
                    if (chapter.isEmpty()) {
                        nomenclature.addChapter(levelCode, version, text);
                    } else {
                        chapter.get().updateWithVersion(version, text);
                    }
                } else if (levelCode.length() == 4) {
                    Optional<HSLevel> heading = nomenclature.getHeading(levelCode);
                    if (heading.isEmpty()) {
                        nomenclature.addHeading(levelCode, version, text);
                    } else {
                        heading.get().updateWithVersion(version, text);
                    }
                } else if (levelCode.length() == 6) {
                    Optional<HSLevel> subHeading = nomenclature.getSubHeading(levelCode);
                    if (subHeading.isEmpty()) {
                        nomenclature.addSubHeading(levelCode, version, text);
                    } else {
                        subHeading.get().updateWithVersion(version, text);
                    }
                }
            } catch (IllegalArgumentException e) {
                LOGGER.info("Skipping level code {} while loading HSCode classification because it is not valid.", levelCode);
            }
        }
    }

    @Override
    public HSNomenclature getHSNomenclature() {
        return hsNomenclature;
    }

    @Override
    public List<HSCodeWithDescription> getAllHsCodes() {
        List<HSCodeWithDescription> result = new ArrayList<>();
        for (HSLevel chapter : hsNomenclature.getChapters()) {
            result.add(new HSCodeWithDescription(chapter.getNomenclatureCode(), chapter.getDescription()));
            for (HSLevel heading : chapter.getChildren().values()) {
                result.add(new HSCodeWithDescription(heading.getNomenclatureCode(), heading.getDescription()));
                for (HSLevel subHeading : heading.getChildren().values()) {
                    result.add(new HSCodeWithDescription(subHeading.getNomenclatureCode(), subHeading.getDescription()));
                }
            }
        }
        return result;
    }

    @Override
    public boolean validHSCode(HSCode hsCode, HSVersion version) {
        String chapterCode = hsCode.chapterCode();
        String headingCode = hsCode.headingCode();
        String subHeadingCode = hsCode.subHeadingCode();
        if (subHeadingCode != null) {
            Optional<HSLevel> chapter = hsNomenclature.getChapter(chapterCode);
            if (chapter.isPresent() && chapter.get().getVersions().contains(version)) {
                Optional<HSLevel> heading = hsNomenclature.getHeading(chapterCode + headingCode);
                if (heading.isPresent() && heading.get().getVersions().contains(version)) {
                    Optional<HSLevel> subHeading = hsNomenclature.getSubHeading(chapterCode + headingCode + subHeadingCode);
                    return subHeading.isPresent() && subHeading.get().getVersions().contains(version);
                }
            }
            return false;
        }
        if (headingCode != null) {
            Optional<HSLevel> chapter = hsNomenclature.getChapter(chapterCode);
            if (chapter.isPresent() && chapter.get().getVersions().contains(version)) {
                Optional<HSLevel> heading = hsNomenclature.getHeading(chapterCode + headingCode);
                return heading.isPresent() && heading.get().getVersions().contains(version);
            }
            return false;
        }

        Optional<HSLevel> chapter = hsNomenclature.getChapter(chapterCode);
        return chapter.isPresent() && chapter.get().getVersions().contains(version);
    }

    @Override
    public Collection<HSCode> convertHSCode(HSCode hsCode, HSVersion fromVersion, HSVersion toVersion) {
        if (fromVersion.equals(toVersion)) {
            return List.of(hsCode);
        }
        if (fromVersion.equals(HSVersion.V_2017) && toVersion.equals(HSVersion.V_2022)) {
            return conversionsFrom2017to2022.get(hsCode);
        }
        return List.of();
    }
}
