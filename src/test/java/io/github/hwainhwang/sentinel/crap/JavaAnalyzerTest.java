package io.github.hwainhwang.sentinel.crap;

import static io.github.hwainhwang.sentinel.crap.Models.CallableKind.CONSTRUCTOR;
import static io.github.hwainhwang.sentinel.crap.Models.CallableKind.LAMBDA;
import static io.github.hwainhwang.sentinel.crap.Models.CallableKind.METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JavaAnalyzerTest {
    @Test
    void inventoriesConstructorsOverloadsAndLambdasWithPositionlessIdentity() {
        String source = """
                package demo;
                import java.util.function.IntUnaryOperator;
                class Sample {
                    Sample(int value) {}
                    int work(int value) { return value; }
                    int work(String value) { return value.length(); }
                    int parent(boolean flag) {
                        IntUnaryOperator child = value -> {
                            if (flag) { return value + 1; }
                            return value;
                        };
                        if (flag) { return child.applyAsInt(1); }
                        return 0;
                    }
                }
                """;

        List<Models.CallableDefinition> definitions = analyze(source, "src/demo/Sample.java");

        assertEquals(5, definitions.size());
        assertEquals(Set.of(CONSTRUCTOR, METHOD, LAMBDA), kinds(definitions));
        assertTrue(hasDescriptor(definitions, "<init>", "(I)V"));
        assertTrue(hasDescriptor(definitions, "work", "(I)I"));
        assertTrue(hasDescriptor(definitions, "work", "(Ljava/lang/String;)I"));
        assertEquals(2, named(definitions, "parent").complexity());
        assertEquals(2, byKind(definitions, LAMBDA).complexity());
        assertTrue(definitions.stream().allMatch(item -> !item.identity().callableId().contains("@")));
    }

    @Test
    void countsTheFullDecisionMatrixAndDoesNotCountDefaultCase() {
        String source = """
                class Decisions {
                    int all(boolean a, boolean b, int[] values) {
                        if (a) {}
                        for (int i = 0; i < values.length; i++) {}
                        for (int value : values) {}
                        while (a) { a = false; }
                        do { b = false; } while (b);
                        try { values[0] = 1; } catch (RuntimeException error) {}
                        int first = a ? 1 : 0;
                        switch (first) {
                            case 1: break;
                            default: break;
                        }
                        return a && b || values.length > 0 ? 1 : 0;
                    }
                }
                """;

        Models.CallableDefinition definition = named(analyze(source, "Decisions.java"), "all");

        assertEquals(12, definition.complexity());
    }

    @Test
    void recordsSecondaryAndMemberClassOwnership() {
        String source = """
                package demo;
                class Primary {
                    class Member { void nested() {} }
                }
                class Secondary { Secondary() {} }
                """;

        List<Models.CallableDefinition> definitions = analyze(source, "src/demo/Primary.java");
        Set<String> owners = definitions.stream()
                .map(item -> item.identity().owner())
                .collect(Collectors.toSet());

        assertEquals(Set.of("demo.Primary$Member", "demo.Secondary"), owners);
    }

    @Test
    void nestedClassAndDistinctSameLineLambdasHaveIndependentComplexity() {
        String source = """
                import java.util.function.IntUnaryOperator;
                class Nested {
                    int parent() {
                        class Local { int child(boolean flag) { if (flag) { return 1; } return 0; } }
                        IntUnaryOperator first = value -> value > 0 ? value : 0; IntUnaryOperator second = value -> value + 1;
                        return first.applyAsInt(new Local().child(true)) + second.applyAsInt(1);
                    }
                }
                """;

        List<Models.CallableDefinition> definitions = analyze(source, "Nested.java");
        List<Models.CallableDefinition> lambdas = definitions.stream()
                .filter(item -> item.identity().kind() == LAMBDA)
                .toList();

        assertEquals(1, named(definitions, "parent").complexity());
        assertEquals(2, named(definitions, "child").complexity());
        assertEquals(List.of(2, 1), lambdas.stream().map(Models.CallableDefinition::complexity).toList());
    }

    @Test
    void inventoriesLambdaInAFieldInitializerWithoutInventingAMethodOwner() {
        String source = """
                import java.util.function.Supplier;
                class FieldLambda { Supplier<Integer> value = () -> 1; }
                """;

        Models.CallableDefinition lambda = byKind(analyze(source, "FieldLambda.java"), LAMBDA);

        assertEquals("FieldLambda", lambda.identity().owner());
        assertTrue(lambda.identity().callableId().startsWith("java:v2:"));
        assertEquals(1, lambda.complexity());
    }

    @Test
    void preservesBomCrLfAndMultibyteBytesInHalfOpenRanges() {
        String method = "int 이름() {\r\n        return 1;\r\n    }";
        String text = "\ufeffclass Sample {\r\n    " + method + "\r\n}\r\n";
        byte[] source = text.getBytes(StandardCharsets.UTF_8);

        Models.CallableDefinition definition =
                JavaAnalyzer.analyze(source, "src/Sample.java").get(0);
        byte[] actual = java.util.Arrays.copyOfRange(
                source,
                Math.toIntExact(definition.sourceRange().startByte()),
                Math.toIntExact(definition.sourceRange().endByte()));

        assertEquals(method, new String(actual, StandardCharsets.UTF_8));
        assertTrue(definition.sourceRange().startByte() >= 3);
    }

    @Test
    void identicalLambdaSitesFailClosedInsteadOfUsingLineOrOrdinal() {
        String source = """
                import java.util.function.Supplier;
                class Duplicate {
                    static int combine(Supplier<Integer> left, Supplier<Integer> right) {
                        return left.get() + right.get();
                    }
                    int value() {
                        return combine(() -> 1, () -> 1);
                    }
                }
                """;

        JavaAnalysisException error = assertThrows(
                JavaAnalysisException.class,
                () -> analyze(source, "Duplicate.java"));

        assertTrue(error.getMessage().contains("identityAmbiguous"));
    }

    @Test
    void movingAUniqueLambdaDoesNotChangeItsCallableId() {
        String compact = """
                import java.util.function.Supplier;
                class Moved { int value() { Supplier<Integer> value = () -> 7; return value.get(); } }
                """;
        String moved = """
                import java.util.function.Supplier;

                class Moved {
                    int value() {
                        // the lambda moved but its semantics did not
                        Supplier<Integer> value = () -> 7;
                        return value.get();
                    }
                }
                """;

        String compactId = byKind(analyze(compact, "Moved.java"), LAMBDA).identity().callableId();
        String movedId = byKind(analyze(moved, "Moved.java"), LAMBDA).identity().callableId();

        assertEquals(compactId, movedId);
    }

    @Test
    void changingOnlyLambdaBodyDoesNotChangeItsSemanticSite() {
        String first = """
                import java.util.function.Supplier;
                class StableBody { int value() { Supplier<Integer> value = () -> 7; return value.get(); } }
                """;
        String changed = """
                import java.util.function.Supplier;
                class StableBody { int value() { Supplier<Integer> value = () -> 8; return value.get(); } }
                """;

        String firstId = byKind(analyze(first, "StableBody.java"), LAMBDA).identity().callableId();
        String changedId = byKind(analyze(changed, "StableBody.java"), LAMBDA).identity().callableId();

        assertEquals(firstId, changedId);
    }

    @Test
    void movingOrEditingNamedMethodBodyDoesNotChangeCallableId() {
        String first = "class StableNamed { int value() { return 1; } }";
        String changed = """
                class StableNamed {

                    int value() {
                        return 2;
                    }
                }
                """;

        String firstId = named(analyze(first, "StableNamed.java"), "value")
                .identity()
                .callableId();
        String changedId = named(analyze(changed, "StableNamed.java"), "value")
                .identity()
                .callableId();

        assertEquals(firstId, changedId);
    }

    @Test
    void rejectsInvalidUtf8SyntaxAndModulePaths() {
        assertThrows(
                JavaAnalysisException.class,
                () -> JavaAnalyzer.analyze(new byte[] {(byte) 0xff}, "Bad.java"));
        assertThrows(
                JavaAnalysisException.class,
                () -> analyze("class Bad { void broken( {}", "Bad.java"));
        assertThrows(
                JavaAnalysisException.class,
                () -> analyze("class Bad { int broken() { return \"not an int\"; } }", "Bad.java"));
        for (String path : List.of("/Bad.java", "./Bad.java", "a\\Bad.java", "a/../Bad.java")) {
            assertThrows(
                    JavaAnalysisException.class,
                    () -> analyze("class Bad {}", path));
        }
    }

    @Test
    void semanticAnalysisDoesNotExecuteStaticInitializers() {
        String source = """
                class NotExecuted {
                    static Object marker = failIfExecuted();
                    static Object failIfExecuted() { throw new AssertionError("must not run"); }
                    int value() { return 1; }
                }
                """;

        List<Models.CallableDefinition> definitions = analyze(source, "NotExecuted.java");

        assertEquals(2, definitions.size());
        assertTrue(definitions.stream().anyMatch(
                item -> item.identity().callableName().equals("value")));
    }

    @Test
    void decisionsOutsideADeclaredCallableFailClosed() {
        String fieldInitializer = """
                class FieldDecision {
                    boolean flag = true;
                    int value = flag ? 1 : 0;
                }
                """;
        String initializerBlock = """
                class BlockDecision {
                    { if (System.nanoTime() > 0) { System.out.print(""); } }
                }
                """;

        JavaAnalysisException fieldError = assertThrows(
                JavaAnalysisException.class,
                () -> analyze(fieldInitializer, "FieldDecision.java"));
        JavaAnalysisException blockError = assertThrows(
                JavaAnalysisException.class,
                () -> analyze(initializerBlock, "BlockDecision.java"));

        assertEquals("decisionOutsideCallable", fieldError.getMessage());
        assertEquals("decisionOutsideCallable", blockError.getMessage());
    }

    @Test
    void inventoriesJavaSeventeenRecordsEnumsInterfacesAndSwitchExpressions() {
        String source = """
                record Point(int x) {
                    Point { if (x < 0) { throw new IllegalArgumentException(); } }
                }
                enum Mode {
                    ACTIVE;
                    Mode() {}
                }
                interface Operations {
                    default int normal(boolean flag) { return flag ? 1 : 0; }
                    static int shared() { return 1; }
                    private int hidden() { return 2; }
                    void abstracted();
                }
                final class SwitchValue {
                    int value(int input) {
                        return switch (input) {
                            case 1 -> 1;
                            default -> 0;
                        };
                    }
                }
                """;

        List<Models.CallableDefinition> definitions = analyze(source, "Java17.java");

        assertEquals(7, definitions.size());
        assertTrue(hasDescriptor(definitions, "<init>", "(I)V"));
        assertEquals(2, named(definitions, "normal").complexity());
        assertEquals(1, named(definitions, "abstracted").complexity());
        assertEquals(2, named(definitions, "value").complexity());
    }

    @Test
    void doesNotResolveTypesFromTheSentinelTestProcessClasspath() {
        String source = """
                import org.junit.jupiter.api.Test;
                class AmbientDependency {
                    @Test void hiddenDependency() {}
                }
                """;

        JavaAnalysisException error = assertThrows(
                JavaAnalysisException.class,
                () -> analyze(source, "AmbientDependency.java"));

        assertTrue(error.getMessage().startsWith("sourceSyntaxOrTypeInvalid:"));
    }

    @Test
    void renamingAGenericTypeVariableDoesNotRenumberItsLambda() {
        String first = """
                import java.util.function.Supplier;
                class GenericLambda {
                    <T> Supplier<T> make(T value) {
                        Supplier<T> supplier = () -> value;
                        return supplier;
                    }
                }
                """;
        String renamed = first.replace("<T>", "<U>")
                .replace("Supplier<T>", "Supplier<U>")
                .replace("(T value)", "(U value)");

        String firstId = byKind(analyze(first, "GenericLambda.java"), LAMBDA)
                .identity()
                .callableId();
        String renamedId = byKind(analyze(renamed, "GenericLambda.java"), LAMBDA)
                .identity()
                .callableId();

        assertEquals(firstId, renamedId);
    }

    @Test
    void renamingAGenericTypeVariableInAnExplicitCastDoesNotRenumberItsLambda() {
        String first = """
                import java.util.function.Supplier;
                class CastLambda {
                    <T> Supplier<T> make(T value) {
                        return (Supplier<T>) () -> value;
                    }
                }
                """;
        String renamed = first.replace("<T>", "<U>")
                .replace("Supplier<T>", "Supplier<U>")
                .replace("(T value)", "(U value)");

        String firstId = byKind(analyze(first, "CastLambda.java"), LAMBDA)
                .identity()
                .callableId();
        String renamedId = byKind(analyze(renamed, "CastLambda.java"), LAMBDA)
                .identity()
                .callableId();

        assertEquals(firstId, renamedId);
    }

    @Test
    void changingAConcreteLambdaTargetTypeChangesItsIdentity() {
        String strings = """
                import java.util.function.Supplier;
                class ConcreteLambda {
                    Supplier<String> make() { return () -> "value"; }
                }
                """;
        String integers = strings.replace("Supplier<String>", "Supplier<Integer>")
                .replace("\"value\"", "1");

        String stringId = byKind(analyze(strings, "ConcreteLambda.java"), LAMBDA)
                .identity()
                .callableId();
        String integerId = byKind(analyze(integers, "ConcreteLambda.java"), LAMBDA)
                .identity()
                .callableId();

        assertNotEquals(stringId, integerId);
    }

    @Test
    void changingAnIntersectionLambdaTargetChangesItsIdentity() {
        String first = """
                import java.io.Serializable;
                class IntersectionLambda {
                    interface FirstMarker {}
                    interface SecondMarker {}
                    Object make() {
                        return (Serializable & FirstMarker & Runnable) () -> {};
                    }
                }
                """;
        String second = first.replace(
                "Serializable & FirstMarker & Runnable",
                "Serializable & SecondMarker & Runnable");
        String reordered = first.replace(
                "Serializable & FirstMarker & Runnable",
                "FirstMarker & Runnable & Serializable");

        Models.CallableIdentity firstIdentity =
                byKind(analyze(first, "IntersectionLambda.java"), LAMBDA).identity();
        Models.CallableIdentity secondIdentity =
                byKind(analyze(second, "IntersectionLambda.java"), LAMBDA).identity();
        Models.CallableIdentity reorderedIdentity =
                byKind(analyze(reordered, "IntersectionLambda.java"), LAMBDA).identity();

        assertNotEquals(firstIdentity.semanticSite(), secondIdentity.semanticSite());
        assertNotEquals(firstIdentity.callableId(), secondIdentity.callableId());
        assertEquals(firstIdentity.callableId(), reorderedIdentity.callableId());
    }

    private static List<Models.CallableDefinition> analyze(String source, String path) {
        return JavaAnalyzer.analyze(source.getBytes(StandardCharsets.UTF_8), path);
    }

    private static Set<Models.CallableKind> kinds(List<Models.CallableDefinition> definitions) {
        return definitions.stream().map(item -> item.identity().kind()).collect(Collectors.toSet());
    }

    private static boolean hasDescriptor(
            List<Models.CallableDefinition> definitions, String name, String descriptor) {
        return definitions.stream().anyMatch(
                item -> item.identity().callableName().equals(name)
                        && item.identity().descriptor().equals(descriptor));
    }

    private static Models.CallableDefinition named(
            List<Models.CallableDefinition> definitions, String name) {
        return definitions.stream()
                .filter(item -> item.identity().callableName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Models.CallableDefinition byKind(
            List<Models.CallableDefinition> definitions, Models.CallableKind kind) {
        return definitions.stream()
                .filter(item -> item.identity().kind() == kind)
                .findFirst()
                .orElseThrow();
    }
}
