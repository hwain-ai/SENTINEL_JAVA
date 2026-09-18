package io.github.hwainhwang.sentinel.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Changed-mode narrowing of the production inventory to the requested sources. */
class ProjectMutationTargetsTest {
    private static final String DIGEST = "0".repeat(63) + "1";
    private static final ProductionSource FIRST = new ProductionSource("src/main/java/demo/First.java", DIGEST);
    private static final ProductionSource LAST = new ProductionSource("src/main/java/demo/Last.java", DIGEST);
    private static final List<ProductionSource> INVENTORY = List.of(FIRST, LAST);

    @Test
    void noRequestKeepsTheWholeInventory() {
        assertSame(INVENTORY, ProjectMutationRunner.targets(INVENTORY, null));
    }

    @Test
    void requestedSourcesAreSelectedInInventoryOrder() {
        assertEquals(List.of(LAST), ProjectMutationRunner.targets(INVENTORY, Set.of(LAST.relativePath())));
        assertEquals(INVENTORY, ProjectMutationRunner.targets(
                INVENTORY, Set.of(LAST.relativePath(), FIRST.relativePath())));
    }

    @Test
    void selectedLinesKeepEveryMatchingMutationInScanOrder() {
        MutationCandidate first = new MutationCandidate("a".repeat(64), FIRST.relativePath(), DIGEST, 4, "first", 1);
        MutationCandidate outside = new MutationCandidate("b".repeat(64), FIRST.relativePath(), DIGEST, 8, "outside", 2);
        MutationCandidate second = new MutationCandidate("c".repeat(64), FIRST.relativePath(), DIGEST, 4, "second", 3);
        List<MutationCandidate> candidates = List.of(first, outside, second);

        assertSame(candidates, ProjectMutationRunner.selectedCandidates(candidates, Set.of()));
        assertEquals(List.of(first, second), ProjectMutationRunner.selectedCandidates(candidates, Set.of(4)));
        assertEquals(List.of(), ProjectMutationRunner.selectedCandidates(candidates, Set.of(99)));
    }

    @Test
    void emptyOrUninventoriedRequestsAreRejected() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> ProjectMutationRunner.targets(INVENTORY, Set.of()));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> ProjectMutationRunner.targets(
                        INVENTORY, Set.of(FIRST.relativePath(), "src/main/java/demo/Missing.java")));

        assertEquals("mutationTargetInvalid", empty.getMessage());
        assertEquals("mutationTargetInvalid", unknown.getMessage());
    }
}
