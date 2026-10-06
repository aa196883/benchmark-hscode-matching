package local.lestr.sandbox;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import java.util.*;

/** Local reference data, with explicit fixtures for edition conversions. Never export. */
public final class InMemoryHSCodeService implements HSCodeService {
    public record Conversion(HSCode code, HSVersion from, HSVersion to) {}
    private final HSVersion defaultVersion;
    private final Map<HSVersion, List<HSCodeWithDescription>> editions;
    private final Map<Conversion, List<HSCode>> conversions;

    public InMemoryHSCodeService(HSVersion version, List<HSCodeWithDescription> codes) {
        this(version, Map.of(version, codes), Map.of());
    }

    public InMemoryHSCodeService(HSVersion defaultVersion,
            Map<HSVersion, List<HSCodeWithDescription>> editions,
            Map<Conversion, List<HSCode>> conversions) {
        this.defaultVersion = Objects.requireNonNull(defaultVersion);
        Map<HSVersion, List<HSCodeWithDescription>> copied = new EnumMap<>(HSVersion.class);
        editions.forEach((version, codes) -> {
            Set<HSCode> seen = new HashSet<>();
            for (var code : codes) {
                if (code.hsCode() == null || code.description() == null || code.description().isBlank()
                        || !seen.add(code.hsCode())) throw new IllegalArgumentException("Invalid or duplicate catalogue entry");
            }
            copied.put(version, List.copyOf(codes));
        });
        this.editions = Map.copyOf(copied);
        if (!this.editions.containsKey(defaultVersion)) throw new IllegalArgumentException("Missing default edition");
        Map<Conversion, List<HSCode>> mappings = new HashMap<>();
        conversions.forEach((key, values) -> {
            if (!validHSCode(key.code(), key.from()) || values.stream().anyMatch(code -> !validHSCode(code, key.to())))
                throw new IllegalArgumentException("Conversion must reference supplied catalogue entries");
            mappings.put(key, List.copyOf(values));
        });
        this.conversions = Map.copyOf(mappings);
    }

    @Override public HSNomenclature getHSNomenclature() {
        var nomenclature = new HSNomenclature();
        for (HSVersion version : HSVersion.values()) {
            var rows = editions.getOrDefault(version, List.of()).stream()
                    .sorted(Comparator.comparingInt((HSCodeWithDescription row) -> row.hsCode().toDigits().length())
                            .thenComparing(row -> row.hsCode().toDigits())).toList();
            for (var row : rows) {
                String code = row.hsCode().toDigits();
                Optional<HSLevel> existing = switch (code.length()) {
                    case 2 -> nomenclature.getChapter(code);
                    case 4 -> nomenclature.getHeading(code);
                    case 6 -> nomenclature.getSubHeading(code);
                    default -> throw new IllegalStateException("Unsupported HS depth: " + code);
                };
                if (existing.isPresent()) {
                    existing.get().updateWithVersion(version, row.description());
                    continue;
                }
                try {
                    switch (code.length()) {
                        case 2 -> nomenclature.addChapter(code, version, row.description());
                        case 4 -> nomenclature.addHeading(code, version, row.description());
                        case 6 -> nomenclature.addSubHeading(code, version, row.description());
                    }
                } catch (NoSuchElementException missingParent) {
                    throw new IllegalStateException("Missing parent for " + code + "; supply the full catalogue, not only candidates", missingParent);
                }
            }
        }
        return nomenclature;
    }
    @Override public List<HSCodeWithDescription> getAllHsCodes() { return editions.get(defaultVersion); }
    @Override public boolean validHSCode(HSCode code, HSVersion version) {
        return editions.getOrDefault(version, List.of()).stream().anyMatch(row -> row.hsCode().equals(code));
    }
    @Override public Collection<HSCode> convertHSCode(HSCode code, HSVersion from, HSVersion to) {
        if (from == to) return validHSCode(code, from) ? List.of(code) : List.of();
        var result = conversions.get(new Conversion(code, from, to));
        if (result == null) throw new UnsupportedOperationException("No local conversion fixture for " + code + " " + from + " -> " + to);
        return result;
    }
}
