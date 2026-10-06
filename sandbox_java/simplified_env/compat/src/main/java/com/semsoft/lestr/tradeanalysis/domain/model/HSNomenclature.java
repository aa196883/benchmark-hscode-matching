package com.semsoft.lestr.tradeanalysis.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

public class HSNomenclature {

    private final SortedMap<String, HSLevel> chapters = new TreeMap<>();

    public List<HSLevel> getChapters() {
        return new ArrayList<>(chapters.values());
    }

    public HSLevel addChapter(String chapterCode, HSVersion version, String description) {
        if (chapterCode.length() != 2) {
            throw new IllegalArgumentException("Invalid chapter code");
        }
        HSLevel chapter = new HSLevel(chapterCode, version, description);
        chapters.put(chapterCode, chapter);
        return chapter;
    }

    public HSLevel addHeading(String headingCode, HSVersion version, String description) {
        if (headingCode.length() != 4) {
            throw new IllegalArgumentException("Invalid heading code");
        }
        HSLevel chapter = getChapter(headingCode.substring(0, 2)).orElseThrow();
        return chapter.addChildLevel(headingCode.substring(2, 4), version, description);
    }

    public HSLevel addSubHeading(String subHeadingCode, HSVersion version, String description) {
        if (subHeadingCode.length() != 6) {
            throw new IllegalArgumentException("Invalid sub-heading code");
        }
        HSLevel heading = getHeading(subHeadingCode.substring(0, 4)).orElseThrow();
        return heading.addChildLevel(subHeadingCode.substring(4, 6), version, description);
    }

    public Optional<HSLevel> getChapter(String chapterCode) {
        if (chapterCode.length() != 2) {
            return Optional.empty();
        }
        return Optional.ofNullable(chapters.get(chapterCode));
    }

    public Optional<HSLevel> getHeading(String headingCode) {
        if (headingCode.length() != 4) {
            return Optional.empty();
        }
        return getChapter(headingCode.substring(0, 2)).map(c -> c.getChild(headingCode.substring(2, 4)));
    }

    public Optional<HSLevel> getSubHeading(String subHeadingCode) {
        if (subHeadingCode.length() != 6) {
            return Optional.empty();
        }
        return getHeading(subHeadingCode.substring(0, 4)).map(c -> c.getChild(subHeadingCode.substring(4, 6)));
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        printLevels(stringBuilder, chapters.entrySet(), 0);
        return stringBuilder.toString();
    }

    private void printLevels(StringBuilder stringBuilder, Set<Map.Entry<String, HSLevel>> entries, int depth) {
        String indent = StringUtils.repeat("  ", depth);
        for (Map.Entry<String, HSLevel> entry : entries) {
            HSLevel hsLevel = entry.getValue();
            stringBuilder.append(indent);
            stringBuilder.append(hsLevel.getNomenclatureCode());
            stringBuilder.append(" (");
            stringBuilder.append(hsLevel.getVersions().stream().sorted().map(HSVersion::toString).collect(Collectors.joining(",")));
            stringBuilder.append(") ");
            stringBuilder.append(hsLevel.getDescription());
            stringBuilder.append("\n");
            printLevels(stringBuilder, hsLevel.getChildren().entrySet(), depth + 1);
        }
    }

}
