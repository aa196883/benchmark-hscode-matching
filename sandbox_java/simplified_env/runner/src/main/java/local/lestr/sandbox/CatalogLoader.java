package local.lestr.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import java.io.*;
import java.util.*;

/** Reads consecutive JSON objects, including pretty-printed POC JSONL. Caller owns the stream. */
public final class CatalogLoader {
    private CatalogLoader() {}
    public static InMemoryHSCodeService load(InputStream input, HSVersion version) throws IOException {
        var mapper = new ObjectMapper();
        var rows = new ArrayList<HSCodeWithDescription>();
        try (var parser = mapper.getFactory().createParser(input)) {
            parser.disable(com.fasterxml.jackson.core.JsonParser.Feature.AUTO_CLOSE_SOURCE);
            while (parser.nextToken() != null) {
                JsonNode row = mapper.readTree(parser);
                if (!row.isObject() || !row.path("code").isTextual() || !row.path("description").isTextual()
                        || !row.path("edition").asText().equals(version.toString())
                        || !row.path("language").asText().equals("en"))
                    throw new IllegalArgumentException("Expected catalogue objects with code, description, edition=" + version + ", language=en");
                rows.add(new HSCodeWithDescription(HSCode.hsCode(row.get("code").textValue()), row.get("description").textValue()));
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Empty catalogue");
        return new InMemoryHSCodeService(version, rows);
    }
}
