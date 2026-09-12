package io.github.hwainhwang.sentinel.crap;

/** Fail-closed error for unsafe or invalid coverage input. */
public final class CoverageFormatException extends RuntimeException {
    public CoverageFormatException(String message) {
        super(message);
    }

    public CoverageFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
