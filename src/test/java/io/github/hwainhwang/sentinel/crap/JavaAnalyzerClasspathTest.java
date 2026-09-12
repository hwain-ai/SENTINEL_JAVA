package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaAnalyzerClasspathTest {
    @Test
    void resolvesOnlyAnExplicitlySuppliedDependencyClasspath() {
        Map<String, byte[]> source = Map.of(
                "src/main/java/sample/UsesDependency.java",
                ("package sample;"
                        + " import io.github.hwainhwang.sentinel.crap.ExactCrap;"
                        + " class UsesDependency { ExactCrap value() { return null; } }")
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(JavaAnalysisException.class, () -> JavaAnalyzer.analyzeAll(source));
        List<Models.CallableDefinition> definitions = JavaAnalyzer.analyzeAll(
                source, List.of(Path.of("target/classes")));

        assertEquals(1, definitions.size());
        assertEquals("()Lio/github/hwainhwang/sentinel/crap/ExactCrap;",
                definitions.get(0).identity().descriptor());
    }
}
