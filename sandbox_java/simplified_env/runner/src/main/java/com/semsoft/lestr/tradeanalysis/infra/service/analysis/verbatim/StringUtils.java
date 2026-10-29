package com.semsoft.lestr.tradeanalysis.infra.service.analysis.verbatim;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

public class StringUtils {
    private static final Pattern TRIM_UNICODE_PATTERN = Pattern.compile("^\\p{Blank}*(.*)\\p{Blank}*$", UNICODE_CHARACTER_CLASS);
    private static final Pattern SPLIT_SPACE_UNICODE_PATTERN = Pattern.compile("\\p{Blank}+", UNICODE_CHARACTER_CLASS);

    public List<String> tokenize(String text) {
        return trimSplitUnicodeBySpace(removePunctuations(text.toLowerCase(Locale.ROOT)));
    }

    public String removePunctuations(String source) {
        return source.replaceAll("\\p{IsPunctuation}", "");
    }

    public List<String> trimSplitUnicodeBySpace(String str) {
        Matcher trimMatcher = TRIM_UNICODE_PATTERN.matcher(str);
        boolean ignored = trimMatcher.matches();
        return Arrays.stream(SPLIT_SPACE_UNICODE_PATTERN.split(trimMatcher.group(1))).toList();
    }
}
