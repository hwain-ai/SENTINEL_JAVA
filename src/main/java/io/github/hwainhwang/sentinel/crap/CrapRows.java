package io.github.hwainhwang.sentinel.crap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared exact and deterministic ordering for CRAP result rows. */
public final class CrapRows {
    private CrapRows() {
        throw new AssertionError("no instances");
    }

    public static List<Models.CrapRow> sort(List<Models.CrapRow> input) {
        if (input == null) {
            throw new IllegalArgumentException("rowsMissing");
        }
        List<Models.CrapRow> rows = new ArrayList<>(input.size());
        Set<Identity> identities = new HashSet<>();
        for (Models.CrapRow row : input) {
            if (row == null) {
                throw new IllegalArgumentException("rowMissing");
            }
            Identity identity = new Identity(
                    row.moduleRelativePath(), row.sourceStartByte(), row.callableId());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("identityAmbiguous");
            }
            rows.add(row);
        }
        rows.sort(CrapRows::compare);
        return List.copyOf(rows);
    }

    private static int compare(Models.CrapRow left, Models.CrapRow right) {
        int knownOrder = Boolean.compare(left.known(), right.known());
        if (knownOrder != 0) {
            return knownOrder;
        }
        if (left.known()) {
            int riskOrder = right.numerator()
                    .multiply(left.denominator())
                    .compareTo(left.numerator().multiply(right.denominator()));
            if (riskOrder != 0) {
                return riskOrder;
            }
        }
        return compareIdentity(left, right);
    }

    private static int compareIdentity(Models.CrapRow left, Models.CrapRow right) {
        int path = SemanticSite.compareUtf8(left.moduleRelativePath(), right.moduleRelativePath());
        if (path != 0) {
            return path;
        }
        int start = Long.compare(left.sourceStartByte(), right.sourceStartByte());
        if (start != 0) {
            return start;
        }
        return SemanticSite.compareUtf8(left.callableId(), right.callableId());
    }

    private record Identity(String path, long start, String callableId) {}
}
