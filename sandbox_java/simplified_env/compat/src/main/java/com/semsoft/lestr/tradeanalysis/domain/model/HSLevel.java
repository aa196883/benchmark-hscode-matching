package com.semsoft.lestr.tradeanalysis.domain.model;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import lombok.Getter;

import org.jspecify.annotations.Nullable;
import java.util.*;
import java.util.regex.Pattern;

import static com.semsoft.lestr.shared.kernel.goods.HSCode.hsCode;

@Getter
public class HSLevel implements Comparable<HSLevel> {

    private static final Pattern LEVEL_CODE_PATTERN = Pattern.compile("\\d{2}");

    @Nullable
    private final HSLevel parent;
    private final SortedMap<String, HSLevel> children = new TreeMap<>();
    private final String levelCode;
    private final Set<HSVersion> versions = new HashSet<>();
    private String description;

    public HSLevel(String levelCode, HSVersion version, String description) {
        this(null, levelCode, version, description);
    }

    private HSLevel(@Nullable HSLevel parent, String levelCode, HSVersion version, String description) {
        if (!LEVEL_CODE_PATTERN.matcher(levelCode).matches()) {
            throw new IllegalArgumentException("Invalid level code: " + levelCode);
        }
        this.levelCode = levelCode;
        this.versions.add(version);
        this.description = description;
        this.parent = parent;
    }

    public HSCode getNomenclatureCode() {
        HSCode hsCode;
        if (parent != null) {
            hsCode = parent.getNomenclatureCode().resolve(levelCode);
        } else {
            hsCode = hsCode(levelCode);
        }
        return hsCode;
    }

    public HSLevel addChildLevel(String levelCode, HSVersion version, String description) {
        HSLevel childLevel = new HSLevel(this, levelCode, version, description);
        children.put(levelCode, childLevel);
        return childLevel;
    }

    public HSLevel getChild(String levelCode) {
        return children.get(levelCode);
    }

    public void updateWithVersion(HSVersion version, String description) {
        if (!this.versions.contains(version)) {
            if (this.versions.stream()
                    .allMatch(existing -> existing.compareTo(version) < 0)) {
                this.description = description;
            }
            this.versions.add(version);
        }
    }

    @Override
    public int compareTo(HSLevel hsLevel) {
        return this.levelCode.compareTo(hsLevel.levelCode);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HSLevel that = (HSLevel) o;
        return Objects.equals(parent, that.parent) && Objects.equals(levelCode, that.levelCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parent, levelCode);
    }
}
