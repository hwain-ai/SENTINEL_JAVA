package io.github.hwainhwang.sentinel.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommitSequenceTest {
    private static final byte[] KEY = HexFormat.of().parseHex(EvidenceFixtures.CLEANUP_KEY_HEX);

    @Test
    void buildsAndAuthenticatesTheFirstSpecGoldenAllocation() {
        byte[] payload = CommitSequence.build("1", KEY);

        assertEquals(
                "7b22686d6163536861323536223a2231353065373833336433666165323436343532643738326664393235353339326265326164393462303463346239346165356262333734623364643762303632222c226c617374416c6c6f6361746564223a2231222c2276657273696f6e223a22636f6d6d69742d73657175656e63652d7631227d0a",
                HexFormat.of().formatHex(payload));
        assertEquals(BigInteger.ONE, CommitSequence.validate(payload, KEY));
    }

    @Test
    void acceptsTheMaximumUnsigned64BitValue() {
        String maximum = "18446744073709551615";
        byte[] payload = CommitSequence.build(maximum, KEY);

        assertEquals(
                "7b22686d6163536861323536223a2262653566636661623439363436336232626434303536663835336531653436393232373038653334303931623832363363343764616331363166373934376236222c226c617374416c6c6f6361746564223a223138343436373434303733373039353531363135222c2276657273696f6e223a22636f6d6d69742d73657175656e63652d7631227d0a",
                HexFormat.of().formatHex(payload));
        assertEquals(new BigInteger(maximum), CommitSequence.validate(payload, KEY));
    }

    @Test
    void rejectsLegacyZeroOverflowAndTampering() {
        assertCode("commitSequenceInvalid", () -> CommitSequence.build("0", KEY));
        assertCode("commitSequenceInvalid", () -> CommitSequence.build("01", KEY));
        assertCode("commitSequenceInvalid", () -> CommitSequence.build(
                "18446744073709551616", KEY));

        String alias = "{\"hmac\":\"" + "a".repeat(64)
                + "\",\"lastAllocated\":\"1\",\"version\":\"commit-sequence-v1\"}\n";
        assertCode("commitSequenceFieldsInvalid", () -> CommitSequence.validate(
                alias.getBytes(StandardCharsets.UTF_8), KEY));

        byte[] tampered = CommitSequence.build("1", KEY);
        tampered[23] = tampered[23] == '0' ? (byte) '1' : (byte) '0';
        assertCode("commitSequenceHmacMismatch", () -> CommitSequence.validate(tampered, KEY));
    }

    @Test
    void validatesHighWaterStateWhileAllowingAllocationGaps() {
        assertEquals(BigInteger.ZERO, CommitSequence.validateState(null, KEY, List.of(), List.of()));
        assertEquals(new BigInteger("5"), CommitSequence.validateState(
                CommitSequence.build("5", KEY), KEY, List.of("1", "3"), List.of("4")));
        assertCode("commitSequenceMissing", () -> CommitSequence.validateState(
                null, KEY, List.of("1"), List.of()));
        assertCode("commitSequenceRollback", () -> CommitSequence.validateState(
                CommitSequence.build("3", KEY), KEY, List.of("4"), List.of()));
        assertCode("commitSequenceDuplicate", () -> CommitSequence.validateState(
                CommitSequence.build("3", KEY), KEY, List.of("2", "2"), List.of()));
        assertCode("evidenceCommitSequenceInvalid", () -> CommitSequence.validateState(
                CommitSequence.build("3", KEY), KEY, List.of("0"), List.of()));
    }

    private static void assertCode(String expected, Runnable operation) {
        EvidenceContractException error = assertThrows(
                EvidenceContractException.class, operation::run);
        assertEquals(expected, error.code());
    }
}
