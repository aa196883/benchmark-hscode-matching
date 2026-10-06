package com.semsoft.lestr.shared.kernel.goods;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A code from the <a href="https://en.wikipedia.org/wiki/Harmonized_System">Harmonized Commodity Description and Coding System</a>.
 */
@Value
public class HSCode implements Serializable {

    public static final String HSCODE_REGEXP = "^(\\d{2})|(\\d{2})\\.?(\\d{2})|(\\d{2})(\\d{2})\\.?(\\d{2})$";
    private static final Pattern HS_CODE_PATTERN = Pattern.compile(HSCODE_REGEXP);

    String chapterCode;
    @Nullable
    String headingCode;
    @Nullable
    String subHeadingCode;

    private HSCode(String chapterCode, @Nullable String headingCode, @Nullable String subHeadingCode) {
        this.chapterCode = chapterCode;
        this.headingCode = headingCode;
        this.subHeadingCode = subHeadingCode;
    }

    /**
     * Same as {@link #valueOf(String)} but it can be static imported.
     *
     * @param codeAsString HS code as string value
     * @return Corresponding instance of HSCode.
     * @throws IllegalArgumentException thrown if string is not a valid HS code.
     */
    public static HSCode hsCode(String codeAsString) {
        return valueOf(codeAsString);
    }

    /**
     * Returns an instance of HSCode from its representation in String.
     *
     * @param codeAsString HS code as string value
     * @return Corresponding instance of HSCode.
     * @throws IllegalArgumentException thrown if string is not a valid HS code.
     */
    public static HSCode valueOf(String codeAsString) {
        Matcher matcher = HS_CODE_PATTERN.matcher(codeAsString);
        if (matcher.matches()) {
            if (matcher.group(1) != null) {
                return new HSCode(matcher.group(1), null, null);
            } else if (matcher.group(2) != null) {
                return new HSCode(matcher.group(2), matcher.group(3), null);
            } else {
                return new HSCode(matcher.group(4), matcher.group(5), matcher.group(6));
            }
        } else {
            throw new IllegalArgumentException("Code " + codeAsString + " is not a valid Harmonized System code.");
        }
    }

    private int getCodeDepth() {
        int depth = 1;
        if (headingCode != null) {
            depth++;
        }
        if (subHeadingCode != null) {
            depth++;
        }
        return depth;
    }

    public HSCode resolve(String relativeCode) {
        Matcher matcher = HS_CODE_PATTERN.matcher(relativeCode);
        if (matcher.matches()) {
            int totalDepth = getCodeDepth() + getRelativeCodeDepth(relativeCode);
            if (totalDepth <= 3) {
                return HSCode.hsCode(this.toDigits() + relativeCode);
            } else {
                throw new IllegalArgumentException("Code " + relativeCode + " is not a valid relative Harmonized System code (depth to long: " + totalDepth + ").");
            }
        } else {
            throw new IllegalArgumentException("Code " + relativeCode + " is not a valid relative Harmonized System code (invalid code).");
        }
    }

    private int getRelativeCodeDepth(String relativeCode) {
        return relativeCode.length() % 2;
    }

    public String toDigits() {
        String code = chapterCode;
        if (headingCode != null) {
            code += headingCode;
        }
        if (subHeadingCode != null) {
            code += subHeadingCode;
        }
        return code;
    }

    @Override
    public String toString() {
        String code = chapterCode;
        if (headingCode != null) {
            if (subHeadingCode == null) {
                code += ".";
            }
            code += headingCode;
        }
        if (subHeadingCode != null) {
            code += ".";
            code += subHeadingCode;
        }
        return code;
    }
}
