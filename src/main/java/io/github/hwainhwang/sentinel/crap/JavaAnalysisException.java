package io.github.hwainhwang.sentinel.crap;

/** Fail-closed error for invalid or ambiguous Java source analysis. */
public final class JavaAnalysisException extends RuntimeException {
    public JavaAnalysisException(String message) {
        super(message);
    }

    public JavaAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
