package com.diagramio.domain.diagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramio.domain.exception.InvalidInputException;
import org.junit.jupiter.api.Test;

class DiagramDataTest {

    @Test
    void ofAcceptsObjectWithNodesAndEdgesArrays() {
        String json = "{\"nodes\": [{\"id\": \"n1\"}], \"edges\": [], \"meta\": {\"zoom\": 1}}";

        assertThat(DiagramData.of(json).json()).isEqualTo(json);
    }

    @Test
    void ofRejectsWrongStructure() {
        for (String json : new String[] {"{\"foo\": 1}", "{\"nodes\": \"abc\", \"edges\": []}",
                "{\"nodes\": []}", "{\"nodes\": [], \"edges\": {}}", "{\"nodes\": null, \"edges\": []}",
                "null", "\"{\\\"nodes\\\": []}\"", "[]", "not json", "", "   ", null}) {
            assertThatThrownBy(() -> DiagramData.of(json))
                    .as(String.valueOf(json))
                    .isInstanceOf(InvalidInputException.class)
                    .hasMessageStartingWith("data must be");
        }
    }

    @Test
    void ofRejectsMoreThanTwoMegabytes() {
        String padding = "x".repeat(DiagramData.MAX_BYTES);
        String json = "{\"nodes\": [], \"edges\": [], \"pad\": \"" + padding + "\"}";

        assertThatThrownBy(() -> DiagramData.of(json))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("data must not exceed 2 MB");
    }

    @Test
    void emptyIsValidAndMatchesSpecDefault() {
        assertThat(DiagramData.of(DiagramData.EMPTY.json())).isEqualTo(DiagramData.EMPTY);
        assertThat(DiagramData.EMPTY.json()).isEqualTo("{\"nodes\": [], \"edges\": []}");
    }

    @Test
    void fromStorageSkipsValidationButRejectsNull() {
        assertThat(DiagramData.fromStorage("{}").json()).isEqualTo("{}");
        assertThatThrownBy(() -> DiagramData.fromStorage(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityFollowsJsonText() {
        assertThat(DiagramData.fromStorage("{\"nodes\": [], \"edges\": []}")).isEqualTo(DiagramData.EMPTY);
        assertThat(DiagramData.fromStorage("{\"nodes\":[],\"edges\":[]}")).isNotEqualTo(DiagramData.EMPTY);
    }

    @Test
    void utf8ByteBoundaryIsInclusiveForAsciiAndVietnamese() {
        String prefix = "{\"nodes\":[],\"edges\":[],\"label\":\"";
        String suffix = "\"}";
        for (String unit : new String[]{"a", "ệ"}) {
            for (int delta : new int[]{-1, 0, 1}) {
                int target = DiagramData.MAX_BYTES + delta;
                int remaining = target - (prefix + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                int width = unit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                String json = prefix + unit.repeat(remaining / width) + "x".repeat(remaining % width) + suffix;
                assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(target);
                if (delta <= 0) {
                    assertThat(DiagramData.of(json).json()).isEqualTo(json);
                } else {
                    assertThatThrownBy(() -> DiagramData.of(json)).isInstanceOf(InvalidInputException.class)
                            .hasMessage("data must not exceed 2 MB");
                }
            }
        }
    }
}
