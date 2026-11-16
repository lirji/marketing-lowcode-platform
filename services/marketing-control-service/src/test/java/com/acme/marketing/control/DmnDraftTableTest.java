package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.control.application.DmnDraftTable;
import com.acme.marketing.platform.error.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 草稿只限制结构和大小，不用保存入口代替显式引擎分析。 */
class DmnDraftTableTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Object> table(Object name, List<?> cells) {
        return Map.of("hitPolicy", "FIRST", "inputs", List.of(Map.of("name", name, "type", "number")),
                "outputs", List.of(Map.of("name", "result", "type", "string")),
                "rules", List.of(Map.of("inputs", cells, "outputs", List.of("\"尚未完成\""))));
    }
    @Test void preservesUnfinishedSemanticsAndExplicitCodes() {
        var value = DmnDraftTable.read(mapper.valueToTree(table("", List.of("unfinished expression"))));
        assertEquals("FIRST", value.hitPolicy().code());
        assertEquals("number", value.inputs().getFirst().type().code());
        assertEquals("unfinished expression", value.rules().getFirst().inputs().getFirst());
        assertEquals(value, mapper.readValue(mapper.writeValueAsString(value), DmnDraftTable.class));
    }
    @Test void rejectsCoercionUnknownFieldsDimensionsAndExcessiveCells() {
        for (Object bad : List.of(table(12, List.of("")), table("x", List.of(12)), table("x", List.of("", "")),
                table("x".repeat(33), List.of("")), table("x", List.of("x".repeat(257))))) {
            assertThrows(DomainException.class, () -> DmnDraftTable.read(mapper.valueToTree(bad)));
        }
        var unknown = new java.util.HashMap<>(table("x", List.of(""))); unknown.put("published", true);
        assertThrows(DomainException.class, () -> DmnDraftTable.read(mapper.valueToTree(unknown)));
    }
    @Test void rejectsTooManyColumnsAndRules() {
        var original = new java.util.HashMap<>(table("x", List.of("")));
        original.put("inputs", java.util.Collections.nCopies(5, Map.of("name", "x", "type", "number")));
        assertThrows(DomainException.class, () -> DmnDraftTable.read(mapper.valueToTree(original)));
        var finalTable = new java.util.HashMap<>(table("x", List.of("")));
        finalTable.put("rules", java.util.Collections.nCopies(33, Map.of("inputs", List.of(""), "outputs", List.of(""))));
        assertThrows(DomainException.class, () -> DmnDraftTable.read(mapper.valueToTree(finalTable)));
    }
    @Test void cannotMutateSavedStructureThroughCallerLists() {
        var cells = new ArrayList<>(List.of(""));
        var rule = new DmnDraftTable.Rule(cells, cells); cells.set(0, "changed");
        assertEquals("", rule.inputs().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> rule.inputs().add("changed"));
        var columns = new ArrayList<DmnDraftTable.Column>(); columns.add(null);
        assertThrows(DomainException.class, () -> new DmnDraftTable(DmnDraftTable.HitPolicy.FIRST, columns,
                List.of(new DmnDraftTable.Column("", DmnDraftTable.ColumnType.STRING)), List.of(rule)));
    }
}
