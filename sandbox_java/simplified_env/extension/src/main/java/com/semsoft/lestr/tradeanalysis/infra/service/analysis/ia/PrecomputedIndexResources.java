package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.semsoft.lestr.tradeanalysis.domain.model.HSCodeWithDescription;
import com.semsoft.lestr.tradeanalysis.domain.model.HSNomenclature;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.sql.SQLException;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** Validates the source index independently of storage. Each stream is owned and closed here. */
public final class PrecomputedIndexResources {
    public static final String MODEL = "text-embedding-3-small";
    @FunctionalInterface public interface ResourceOpener { InputStream open(String name) throws IOException; }
    @FunctionalInterface public interface VectorConsumer {
        void accept(HSCodeWithDescription row, float[] vector) throws SQLException;
    }
    private final HSNomenclature nomenclature;
    private final Map<String, HSCodeWithDescription> byCode;
    final List<HSCodeWithDescription> rows;
    final JsonNode manifest;
    final int dimensions;
    final Integer configuredDimensions;
    private final ResourceOpener resources;

    public static PrecomputedIndexResources packaged(HSCodeService hsCodeService) throws IOException {
        ResourceOpener opener = name -> {
            String resource = "h6_2022/" + name;
            var stream = PrecomputedIndexResources.class.getResourceAsStream(resource);
            if (stream == null) throw new FileNotFoundException("Missing RAG classpath resource: " + resource);
            return stream;
        };
        return new PrecomputedIndexResources(opener, hsCodeService);
    }
    public PrecomputedIndexResources(Path directory, HSCodeService hsCodeService) throws IOException {
        this(name -> Files.newInputStream(directory.resolve(name)), hsCodeService);
    }
    public PrecomputedIndexResources(ResourceOpener resources, HSCodeService hsCodeService) throws IOException {
        this.resources = Objects.requireNonNull(resources);
        Objects.requireNonNull(hsCodeService);
        this.nomenclature = Objects.requireNonNull(hsCodeService.getHSNomenclature());
        var candidates = new TreeMap<String, HSCodeWithDescription>();
        for (var entry : hsCodeService.getAllHsCodes()) {
            String code = entry.hsCode().toDigits();
            if (code.length() != 6 || code.startsWith("98") || code.startsWith("99")
                    || !hsCodeService.validHSCode(entry.hsCode(), HSVersion.V_2022)) continue;
            var candidate = new HSCodeWithDescription(entry.hsCode(), normalized(entry.description()));
            require(candidates.putIfAbsent(code, candidate) == null, "Duplicate HS service code: " + code);
        }
        require(!candidates.isEmpty(), "No eligible HS 2022 candidates");
        byCode = Map.copyOf(candidates);
        rows = List.copyOf(candidates.values());
        try (var input = resources.open("manifest.json")) {
            manifest = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        require(manifest != null && manifest.isObject(), "Invalid index manifest");
        require(manifest.path("schema_version").isIntegralNumber() && manifest.path("schema_version").intValue() == 1
                && "2022".equals(manifest.path("edition").asText()) && "en".equals(manifest.path("language").asText())
                && "contextual_description".equals(manifest.path("text_field").asText()), "Incompatible index scope or schema");
        require("openai".equals(manifest.path("provider").asText()) && MODEL.equals(manifest.path("config").path("model").asText())
                && MODEL.equals(manifest.path("response_model").asText()), "Index must use " + MODEL);
        require("raw_on_disk_l2_in_memory".equals(manifest.path("normalization").asText()), "Unsupported index normalization");
        require(manifest.path("dimensions").isInt() && manifest.path("dimensions").intValue() > 0, "Invalid dimensions");
        this.dimensions = manifest.get("dimensions").intValue();
        JsonNode configured = manifest.path("config").path("dimensions");
        require(configured.isMissingNode() || configured.isNull() || (configured.isInt() && configured.intValue() == dimensions), "Inconsistent configured dimensions");
        this.configuredDimensions = configured.isInt() ? configured.intValue() : null;
        require(descriptionsSha256().equals(manifest.path("source").path("descriptions_sha256").asText()), "Catalogue descriptions differ from the index");
        require(manifest.path("count").isInt() && manifest.path("count").intValue() == rows.size(), "Inconsistent index count");
    }
    HSCodeWithDescription row(String code) { return byCode.get(code); }

    String contextualDescription(HSCodeWithDescription row) {
        String code = row.hsCode().toDigits();
        var chapter = nomenclature.getChapter(code.substring(0, 2)).orElseThrow(
                () -> new IllegalArgumentException("Missing HS chapter for " + code));
        var heading = nomenclature.getHeading(code.substring(0, 4)).orElseThrow(
                () -> new IllegalArgumentException("Missing HS heading for " + code));
        require(chapter.getVersions().contains(HSVersion.V_2022) && heading.getVersions().contains(HSVersion.V_2022),
                "Missing HS 2022 hierarchy for " + code);
        return normalized(chapter.getDescription()) + " > " + normalized(heading.getDescription()) + " > " + row.description();
    }

    private String descriptionsSha256() {
        var texts = rows.stream().map(row -> List.of(row.hsCode().toDigits(), contextualDescription(row))).toList();
        return sha(encode(texts).getBytes(StandardCharsets.UTF_8));
    }

    // Preserve the text used for the existing precomputed embeddings without changing the industrial service.
    private static String normalized(String description) {
        require(description != null && !description.isBlank(), "Missing HS service description");
        return description.strip().replace('"', '\'');
    }

    /** The caller must roll back side effects if validation fails, including the final checksum. */
    public void readVectors(VectorConsumer consumer) throws IOException, SQLException {
        var digest = digest();
        int count = 0;
        try (var input = new DigestInputStream(resources.open("vectors.jsonl"), digest);
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode row = parse(line);
                require(row != null && row.isObject() && count < rows.size() && rows.get(count).hsCode().toDigits().equals(row.path("code").asText()), "Invalid vector order, duplicate or extra code");
                double[] vector = vector(row.path("vector"), dimensions);
                float[] stored = new float[dimensions];
                double normSquared = 0;
                for (int j = 0; j < dimensions; j++) {
                    stored[j] = (float) vector[j];
                    require(Float.isFinite(stored[j]), "Vector incompatible with float32");
                    normSquared += (double) stored[j] * stored[j];
                }
                double norm = Math.sqrt(normSquared);
                require(Double.isFinite(norm) && norm > 0, "Zero float32 vector norm");
                for (int j = 0; j < dimensions; j++) stored[j] = (float) (stored[j] / norm);
                var candidate = rows.get(count++);
                consumer.accept(candidate, stored);
            }
        }
        require(count == rows.size() && HexFormat.of().formatHex(digest.digest()).equals(manifest.path("vectors_sha256").asText()), "Incomplete index or invalid vectors checksum");
    }
    static double[] vector(JsonNode array, int dimensions) {
        require(array.isArray() && array.size() == dimensions, "Invalid vector dimensions");
        double[] vector = new double[dimensions];
        for (int j = 0; j < dimensions; j++) {
            require(array.get(j).isNumber(), "Non-numeric vector component");
            vector[j] = array.get(j).doubleValue();
        }
        validate(vector, dimensions);
        return vector;
    }
    static double validate(double[] vector, int dimensions) {
        require(vector != null && vector.length == dimensions && dimensions > 0, "Invalid vector dimensions");
        double norm = 0;
        for (double value : vector) {
            require(Double.isFinite(value), "Non-finite vector component");
            norm = Math.hypot(norm, value);
        }
        require(Double.isFinite(norm) && norm > 0, "Zero or non-finite vector norm");
        return norm;
    }
    public int size() { return rows.size(); }
}
