package io.github.hwainhwang.sentinel.evidence;

/** Stable fail-closed error raised by the private evidence wire contract. */
public final class EvidenceContractException extends IllegalArgumentException {
    private final String code;

    public EvidenceContractException(String code) {
        super(code);
        this.code = code;
    }

    public EvidenceContractException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
