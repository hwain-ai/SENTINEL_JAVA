package io.github.hwainhwang.sentinel.crap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecGoldenSnapshotTest {
    private static final Path ROOT =
            Path.of("src/test/resources/vendor/sentinel-spec/golden");

    @Test
    void vendoredGoldenBytesMatchTheCurrentSpecSnapshot() throws IOException {
        Map<String, String> expected = Map.of(
                "crap/formula-v1.json",
                "c49493dc25c841af08efd6b1984fdeae47b54a5345e71dea137de96c45fe8884",
                "crap/stable-sort-v1.json",
                "921f87cdb842042fad914147c2d4daded2654175088c9284abc3cff0cbff1f06",
                "gate/mutation-v1.json",
                "10a72c7fc59f3ca89ba9a7c7985c17f156f4aac86f2513f585efbfb46c686dbc",
                "gate/threshold-v1.json",
                "188f7bdad01fb970ab06822a5eadaca5e0a208a8b88409c34300b6fa9b76a5f3");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), sha256(ROOT.resolve(entry.getKey())), entry.getKey());
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
