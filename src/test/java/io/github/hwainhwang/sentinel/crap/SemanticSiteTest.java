package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SemanticSiteTest {
    @Test
    void lambdaDescriptorUsesSemanticContentInsteadOfSourcePosition() {
        String first = SemanticSite.lambdaDescriptor(
                "demo.Sample#work()V",
                "java.util.function.Supplier<java.lang.Integer>",
                "binding:value/arity:0/body-kind:EXPRESSION");
        String moved = SemanticSite.lambdaDescriptor(
                "demo.Sample#work()V",
                "java.util.function.Supplier<java.lang.Integer>",
                "binding:value/arity:0/body-kind:EXPRESSION");

        assertEquals(first, moved);
        assertNotEquals(
                first,
                SemanticSite.lambdaDescriptor(
                        "demo.Sample#work()V",
                        "java.util.function.Supplier<java.lang.Integer>",
                        "binding:other/arity:0/body-kind:EXPRESSION"));
    }

    @Test
    void utf8IdentityRejectsUnpairedSurrogates() {
        assertThrows(IllegalArgumentException.class, () -> SemanticSite.utf8("bad\ud800"));
    }
}
