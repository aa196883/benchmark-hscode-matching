package local.lestr.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class SourceBoundaryTest {
    private final Path root = Path.of(System.getProperty("basedir")).getParent();
    @Test void compatibilityCopiesMatchDocumentedSnapshots() throws Exception {
        var manifest = new ObjectMapper().readTree(root.resolve("reference-manifest.json").toFile());
        var entries = manifest.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            var info = entry.getValue();
            var local = root.resolve(info.get("local_path").asText());
            assertEquals(info.get("local_sha256").asText(), sha(local), "Undocumented compat change: " + local);
            var reference = root.getParent().resolve("lestr_sources").resolve(entry.getKey());
            // Reference snippets are ignored by git, so a fresh checkout can build without them.
            if (Files.exists(reference))
                assertEquals(info.get("reference_sha256").asText(), sha(reference), "Reference changed; review compatibility: " + reference);
        }
    }
    @Test void transferableCodeDoesNotDependOnLocalHarnessOrRedefineIndustrialTypes() throws Exception {
        var source = root.resolve("extension/src/main/java");
        try (var files = Files.walk(source)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                // Strip comments to allow explanatory package documentation.
                text = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                assertFalse(text.contains("local.lestr.sandbox"), "Local dependency in " + file);
                assertFalse(Files.exists(root.resolve("compat/src/main/java").resolve(source.relativize(file))), "Industrial class redefined: " + file);
                var matcher = Pattern.compile("package\\s+([\\w.]+)\\s*;").matcher(text);
                assertTrue(matcher.find(), "Missing package: " + file);
                assertEquals(source.resolve(matcher.group(1).replace('.', '/')).resolve(file.getFileName()), file, "Package/path mismatch");
            }
        }
    }
    private String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
