package io.github.hwainhwang.sentinel.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void matchesSpecGoldenBytesForOrderingControlsAndUnicode() {
        Map<String, Object> value = Map.of(
                "z", "한글/😀",
                "a", "quote\" slash/ backslash\\ nul\0 line\n");

        String expected =
                "7b2261223a2271756f74655c2220736c6173682f206261636b736c6173685c5c206e756c5c7530303030206c696e655c7530303061222c227a223a22ed959ceab8802ff09f9880227d";
        assertEquals(expected, HexFormat.of().formatHex(CanonicalJson.encode(value)));
        assertEquals(value, CanonicalJson.readFile(
                HexFormat.of().parseHex(expected + "0a")));
    }

    @Test
    void preservesUnicodeNormalizationAndSafePrimitives() {
        assertEquals(
                "7b22636f6d706f736564223a22c3a9222c226465636f6d706f736564223a2265cc81227d",
                HexFormat.of().formatHex(CanonicalJson.encode(Map.of(
                        "composed", "é", "decomposed", "é"))));
        assertEquals(
                "5b6e756c6c2c66616c73652c747275652c302c393030373139393235343734303939315d",
                HexFormat.of().formatHex(CanonicalJson.encode(Arrays.asList(
                        null, false, true, 0, 9_007_199_254_740_991L))));
    }

    @Test
    void rejectsUnsupportedNumbersKeysAndUnicodeScalars() {
        assertCode("canonicalJsonTypeInvalid", () -> CanonicalJson.encode(1.0));
        assertCode("canonicalJsonIntegerOutOfRange", () -> CanonicalJson.encode(-1));
        assertCode(
                "canonicalJsonIntegerOutOfRange",
                () -> CanonicalJson.encode(new BigInteger("9007199254740992")));
        assertCode("canonicalJsonKeyInvalid", () -> CanonicalJson.encode(Map.of(1, "value")));
        assertCode("canonicalJsonUnicodeScalarInvalid", () -> CanonicalJson.encode("\ud800"));
    }

    @Test
    void readsOnlyUniqueCanonicalIntegerOnlyUtf8Files() {
        byte[] canonical = "{\"a\":0,\"b\":true}\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(Map.of("a", 0L, "b", true), CanonicalJson.readFile(canonical));

        assertCode("jsonDuplicateKey", () -> CanonicalJson.readFile(
                "{\"a\":0,\"a\":1}\n".getBytes(StandardCharsets.UTF_8)));
        assertCode("jsonIntegerLexemeInvalid", () -> CanonicalJson.readFile(
                "{\"a\":1.0}\n".getBytes(StandardCharsets.UTF_8)));
        assertCode("canonicalJsonIntegerOutOfRange", () -> CanonicalJson.readFile(
                "{\"a\":-1}\n".getBytes(StandardCharsets.UTF_8)));
        assertCode("jsonIntegerOutOfRange", () -> CanonicalJson.readFile(
                "{\"a\":9007199254740992}\n".getBytes(StandardCharsets.UTF_8)));
        assertCode("canonicalJsonMismatch", () -> CanonicalJson.readFile(
                "{\"a\": 0}\n".getBytes(StandardCharsets.UTF_8)));
        assertCode("jsonUtf8Invalid", () -> CanonicalJson.readFile(new byte[] {(byte) 0x80}));
        assertCode("jsonBomForbidden", () -> CanonicalJson.readFile(
                new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}', '\n'}));
    }

    @Test
    void canonicalFileAddsExactlyOneLineFeed() {
        assertArrayEquals(
                "{\"a\":0}\n".getBytes(StandardCharsets.UTF_8),
                CanonicalJson.file(Map.of("a", 0)));
    }

    private static void assertCode(String expected, Runnable operation) {
        EvidenceContractException error = assertThrows(
                EvidenceContractException.class, operation::run);
        assertEquals(expected, error.code());
    }
}
