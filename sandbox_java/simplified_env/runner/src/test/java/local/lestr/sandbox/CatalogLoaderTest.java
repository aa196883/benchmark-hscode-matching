package local.lestr.sandbox;

import com.semsoft.lestr.shared.kernel.goods.HSCode;
import com.semsoft.lestr.tradeanalysis.domain.model.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogLoaderTest {
    private InMemoryHSCodeService load(String text) throws IOException {
        return CatalogLoader.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), HSVersion.V_2022);
    }
    private final String row = """
            {
              "code":"010121", "description":"Élevage", "edition":"2022", "language":"en",
              "contextual_description":"Animals > Horses > Élevage"
            }
            """;
    @Test void acceptsMultilineObjectsAndKeepsOriginalDescriptions() throws Exception {
        var service = load(row + row.replace("010121", "010129"));
        assertEquals(2, service.getAllHsCodes().size());
        assertEquals("Élevage", service.getAllHsCodes().getFirst().description());
        assertTrue(service.validHSCode(HSCode.hsCode("0101.21"), HSVersion.V_2022));
        assertFalse(service.validHSCode(HSCode.hsCode("010121"), HSVersion.V_2017));
        assertFalse(service.validHSCode(HSCode.hsCode("01"), HSVersion.V_2022));
    }
    @Test void rejectsEmptyDuplicateWrongEditionAndNumericCodes() {
        for (String invalid : List.of("", row + row, row.replace("2022", "2017"), row.replace("\"010121\"", "10121"), row.replace("\"en\"", "\"fr\"")))
            assertThrows(IllegalArgumentException.class, () -> load(invalid));
    }
    @Test void incompleteHierarchyAndUnavailableConversionFailExplicitly() throws Exception {
        var service = load(row);
        assertThrows(IllegalStateException.class, service::getHSNomenclature);
        assertThrows(UnsupportedOperationException.class, () -> service.convertHSCode(HSCode.hsCode("010121"), HSVersion.V_2017, HSVersion.V_2022));
        assertEquals(List.of(HSCode.hsCode("010121")), service.convertHSCode(HSCode.hsCode("010121"), HSVersion.V_2022, HSVersion.V_2022));
    }
}
