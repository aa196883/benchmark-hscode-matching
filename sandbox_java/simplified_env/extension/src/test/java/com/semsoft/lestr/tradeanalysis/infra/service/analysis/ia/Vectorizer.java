package com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semsoft.lestr.tradeanalysis.domain.model.HSVersion;
import com.semsoft.lestr.tradeanalysis.domain.spi.HSCodeService;
import com.semsoft.lestr.tradeanalysis.infra.service.HSCodeServiceImpl;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Standalone generation of HS 2022 embeddings; JSONL file as output. */
public final class Vectorizer {
    private static final String MODEL = "text-embedding-3-small";
    private static final int DIMENSIONS = 1536;
    private static final int BATCH_SIZE = 64;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("Usage: Vectorizer OUTPUT_DIRECTORY [LIMIT] (requires OPENAI_API_KEY)");
            return;
        }
        if (args.length > 2) throw new IllegalArgumentException("Expected OUTPUT_DIRECTORY [LIMIT]");
        int limit = args.length == 2 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("OPENAI_API_KEY required");
        var model = OpenAiEmbeddingModel.builder().apiKey(key).modelName(MODEL)
                .dimensions(DIMENSIONS).maxRetries(0).build();
        generate(new HSCodeServiceImpl(), model, Path.of(args[0]), limit);
    }

    static void generate(HSCodeService service, EmbeddingModel model, Path directory, int limit) throws Exception {
        if (limit <= 0) throw new IllegalArgumentException("LIMIT must be positive");
        var nomenclature = service.getHSNomenclature();
        var descriptions = new TreeMap<String, String>();
        for (var entry : service.getAllHsCodes()) {
            String code = entry.hsCode().toDigits();
            if (code.length() != 6 || code.startsWith("98") || code.startsWith("99")
                    || !service.validHSCode(entry.hsCode(), HSVersion.V_2022)) continue;
            String chapter = nomenclature.getChapter(code.substring(0, 2)).orElseThrow().getDescription();
            String heading = nomenclature.getHeading(code.substring(0, 4)).orElseThrow().getDescription();
            descriptions.put(code, normalized(chapter) + " > " + normalized(heading) + " > " + normalized(entry.description()));
        }
        var rows = descriptions.entrySet().stream().limit(limit).toList();
        if (rows.isEmpty()) throw new IllegalArgumentException("No eligible HS 2022 codes");
        // A fresh directory prevents overwriting an existing index, even on failure.
        Files.createDirectory(directory);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var writer = new OutputStreamWriter(new DigestOutputStream(
                Files.newOutputStream(directory.resolve("vectors.jsonl")), digest), StandardCharsets.UTF_8)) {
            for (int offset = 0; offset < rows.size(); offset += BATCH_SIZE) {
                var batch = rows.subList(offset, Math.min(offset + BATCH_SIZE, rows.size()));
                var texts = batch.stream().map(row -> TextSegment.from(row.getValue())).toList();
                var vectors = model.embedAll(texts).content();
                if (vectors.size() != batch.size()) throw new IllegalStateException("Missing embeddings");
                for (int i = 0; i < batch.size(); i++) {
                    float[] vector = vectors.get(i).vector();
                    if (vector.length != DIMENSIONS) throw new IllegalStateException("Unexpected embedding dimensions");
                    var line = JSON.createObjectNode().put("code", batch.get(i).getKey());
                    line.set("vector", JSON.valueToTree(vector));
                    writer.write(JSON.writeValueAsString(line));
                    writer.write('\n');
                }
                writer.flush();
                System.out.printf("%d/%d vectors%n", offset + batch.size(), rows.size());
            }
        }
        // Publish the manifest only after all batches have succeeded.
        var manifest = JSON.createObjectNode();
        manifest.put("schema_version", 1).put("created_at", Instant.now().toString())
                .put("edition", "2022").put("language", "en").put("text_field", "contextual_description")
                .put("provider", "openai").put("response_model", MODEL)
                .put("dimensions", DIMENSIONS).put("count", rows.size())
                .put("normalization", "raw_on_disk_l2_in_memory").put("batch_size", BATCH_SIZE)
                .put("vectors_sha256", HexFormat.of().formatHex(digest.digest()));
        manifest.putObject("config").put("model", MODEL).put("dimensions", DIMENSIONS);
        var texts = rows.stream().map(row -> List.of(row.getKey(), row.getValue())).toList();
        byte[] descriptionHash = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(texts));
        manifest.putObject("source").put("descriptions_sha256", HexFormat.of().formatHex(descriptionHash));
        Files.writeString(directory.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n");
    }

    private static String normalized(String text) {
        return text.strip().replace('"', '\'');
    }
}
