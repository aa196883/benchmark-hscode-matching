package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.util.*;
import static com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia.RagJson.*;

/** Immutable exact cosine index. Loads precomputed resources once; never computes catalogue embeddings. */
public final class PrecomputedEmbeddingIndex {
    public static final String MODEL = "text-embedding-3-small";
    public record Hit(String code, int rank, double score, String description,
                      @JsonProperty("contextual_description") String contextualDescription) {}
    @FunctionalInterface
    public interface ResourceOpener {
        InputStream open(String name) throws IOException;
    }
    private final String location;
    private final RagCatalog catalog;
    private final JsonNode manifest;
    private final List<RagCatalog.Row> rows;
    private final float[][] matrix;
    private final int dimensions;
    private final Integer configuredDimensions;

    public PrecomputedEmbeddingIndex(Path directory, RagCatalog catalog) throws IOException {
        this(directory.toAbsolutePath().normalize().toString(),
                name -> Files.newInputStream(directory.resolve(name)), catalog);
    }
    /** Reads and closes each resource stream; location is an identifier for diagnostics only. */
    public PrecomputedEmbeddingIndex(String location, ResourceOpener resources, RagCatalog catalog) throws IOException {
        this.location = Objects.requireNonNull(location);
        this.catalog = Objects.requireNonNull(catalog);
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
        dimensions = manifest.get("dimensions").intValue();
        JsonNode configured = manifest.path("config").path("dimensions");
        require(configured.isMissingNode() || configured.isNull() || (configured.isInt() && configured.intValue() == dimensions), "Inconsistent configured dimensions");
        configuredDimensions = configured.isInt() ? configured.intValue() : null;
        rows = catalog.candidates();
        require(catalog.descriptionsSha256().equals(manifest.path("source").path("descriptions_sha256").asText()), "Catalogue descriptions differ from the index");
        require(manifest.path("count").isInt() && manifest.path("count").intValue() == rows.size(), "Inconsistent index count");
        matrix = new float[rows.size()][];
        var digest = digest();
        int count = 0;
        try (var input = new DigestInputStream(resources.open("vectors.jsonl"), digest);
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode row = parse(line);
                require(row != null && row.isObject() && count < rows.size() && rows.get(count).code().equals(row.path("code").asText()), "Invalid vector order, duplicate or extra code");
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
                matrix[count++] = stored;
            }
        }
        require(count == rows.size() && HexFormat.of().formatHex(digest.digest()).equals(manifest.path("vectors_sha256").asText()), "Incomplete index or invalid vectors checksum");
    }
    public List<Hit> search(double[] vector, int topK) {
        require(topK > 0, "topK must be positive");
        double norm = validate(vector, dimensions);
        float[] query = new float[dimensions];
        for (int j = 0; j < dimensions; j++) query[j] = (float) (vector[j] / norm);
        double[] scores = new double[rows.size()];
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            double sum = 0;
            for (int j = 0; j < dimensions; j++) sum += (double) matrix[i][j] * query[j];
            // Double accumulation followed by float32 rounding limits order-sensitive drift.
            scores[i] = Math.clamp((float) sum, -1f, 1f);
            order[i] = i;
        }
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> scores[i]).reversed().thenComparing(i -> rows.get(i).code()));
        var result = new ArrayList<Hit>();
        for (int rank = 0; rank < Math.min(topK, order.length); rank++) {
            int i = order[rank]; var row = rows.get(i);
            result.add(new Hit(row.code(), rank + 1, scores[i], row.description(), row.contextualDescription()));
        }
        return List.copyOf(result);
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
    public int dimensions() { return dimensions; }
    public Integer configuredDimensions() { return configuredDimensions; }
    public RagCatalog catalog() { return catalog; }
    public JsonNode manifest() { return manifest.deepCopy(); }
    public String location() { return location; }
    public int size() { return rows.size(); }
}
