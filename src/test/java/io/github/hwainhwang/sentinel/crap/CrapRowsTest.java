package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrapRowsTest {
    @Test
    void sortsUnknownThenExactRiskThenStableIdentity() {
        Models.CrapRow low = known("src/low.java", 2, "low", "1", "3");
        Models.CrapRow near = known(
                "src/near.java", 5, "near", "1000000000000001", "3000000000000000");
        Models.CrapRow high = known("src/high.java", 1, "high", "9", "1");
        Models.CrapRow unknownKorean = Models.CrapRow.unknown(
                "src/한글.java", 7, "unknown-korean", "coverageFileMissing");
        Models.CrapRow unknownAstral = Models.CrapRow.unknown(
                "src/\uD800\uDC00.java", 3, "unknown-astral", "coverageUnitsMissing");

        List<Models.CrapRow> ordered =
                CrapRows.sort(List.of(low, near, unknownAstral, high, unknownKorean));

        assertEquals(
                List.of("unknown-korean", "unknown-astral", "high", "near", "low"),
                ids(ordered));
    }

    @Test
    void usesUtf8BytesForPathAndCallableIdentity() {
        Models.CrapRow astralPath = known("src/\uD800\uDC00.java", 1, "astral-path", "2", "1");
        Models.CrapRow bmpPath = known("src/\uE000.java", 1, "bmp-path", "2", "1");
        assertEquals(List.of("bmp-path", "astral-path"), ids(CrapRows.sort(List.of(astralPath, bmpPath))));

        Models.CrapRow astralId = known("src/same.java", 1, "\uD800\uDC00", "2", "1");
        Models.CrapRow bmpId = known("src/same.java", 1, "\uE000", "2", "1");
        assertEquals(List.of("\uE000", "\uD800\uDC00"), ids(CrapRows.sort(List.of(astralId, bmpId))));
    }

    @Test
    void sourceOffsetPrecedesCallableIdAndDuplicateFinalKeyFails() {
        Models.CrapRow higherOffset = known("src/same.java", 2, "a", "2", "1");
        Models.CrapRow lowerOffset = known("src/same.java", 1, "z", "2", "1");
        Models.CrapRow lowerId = known("src/same.java", 1, "b", "2", "1");
        assertEquals(
                List.of("b", "z", "a"),
                ids(CrapRows.sort(List.of(higherOffset, lowerOffset, lowerId))));

        Models.CrapRow duplicate = known("src/same.java", 1, "same", "2", "1");
        assertThrows(IllegalArgumentException.class, () -> CrapRows.sort(List.of(duplicate, duplicate)));
    }

    @Test
    void validatesSafeOffsetsReducedFractionsAndKnownUnknownExclusivity() {
        Models.CrapRow maximum = known(
                "src/a.java", Models.MAX_SAFE_INTEGER, "a", "0", "1");
        Models.CrapRow beforeMaximum = known(
                "src/a.java", Models.MAX_SAFE_INTEGER - 1, "before", "0", "1");
        assertEquals(Models.MAX_SAFE_INTEGER, maximum.sourceStartByte());
        assertEquals(List.of("before", "a"), ids(CrapRows.sort(List.of(maximum, beforeMaximum))));
        assertThrows(
                IllegalArgumentException.class,
                () -> known("src/a.java", Models.MAX_SAFE_INTEGER + 1, "a", "1", "1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> known("src/a.java", Models.MAX_SAFE_INTEGER + 2, "a", "1", "1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> known("src/a.java", 0, "a", "2", "2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Models.CrapRow(
                        "src/a.java", 0, "a", BigInteger.ONE, BigInteger.ONE, "unknown"));
    }

    private static Models.CrapRow known(
            String path, long start, String id, String numerator, String denominator) {
        return Models.CrapRow.known(
                path, start, id, new BigInteger(numerator), new BigInteger(denominator));
    }

    private static List<String> ids(List<Models.CrapRow> rows) {
        return rows.stream().map(Models.CrapRow::callableId).toList();
    }
}
