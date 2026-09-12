package io.github.hwainhwang.sentinel.evidence;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact integer-only UTF-8 JSON codec shared by authenticated evidence files. */
public final class CanonicalJson {
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final BigInteger MAX_SAFE = BigInteger.valueOf(MAX_SAFE_INTEGER);

    private CanonicalJson() {
        throw new AssertionError("no instances");
    }

    public static byte[] encode(Object value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeValue(output, value);
        return output.toByteArray();
    }

    public static byte[] file(Object value) {
        byte[] body = encode(value);
        byte[] payload = Arrays.copyOf(body, body.length + 1);
        payload[body.length] = '\n';
        return payload;
    }

    public static Object readFile(byte[] payload) {
        Object value = parse(payload);
        if (!Arrays.equals(payload, file(value))) {
            throw new EvidenceContractException("canonicalJsonMismatch");
        }
        return value;
    }

    private static Object parse(byte[] payload) {
        if (payload == null) {
            throw new EvidenceContractException("jsonBytesRequired");
        }
        String text = decode(payload);
        if (text.startsWith("\ufeff")) {
            throw new EvidenceContractException("jsonBomForbidden");
        }
        return new Parser(text).parse();
    }

    private static String decode(byte[] payload) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new EvidenceContractException("jsonUtf8Invalid", error);
        }
    }

    private static void writeValue(ByteArrayOutputStream output, Object value) {
        if (value == null) {
            output.writeBytes("null".getBytes(StandardCharsets.US_ASCII));
        } else if (value instanceof Boolean booleanValue) {
            writeBoolean(output, booleanValue);
        } else if (isInteger(value)) {
            writeInteger(output, value);
        } else {
            writeStructured(output, value);
        }
    }

    private static boolean isInteger(Object value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger;
    }

    private static void writeStructured(ByteArrayOutputStream output, Object value) {
        if (value instanceof String stringValue) {
            writeString(output, stringValue);
        } else if (value instanceof List<?> listValue) {
            writeList(output, listValue);
        } else if (value instanceof Map<?, ?> mapValue) {
            writeMap(output, mapValue);
        } else {
            throw new EvidenceContractException("canonicalJsonTypeInvalid");
        }
    }

    private static void writeBoolean(ByteArrayOutputStream output, boolean value) {
        output.writeBytes((value ? "true" : "false").getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeInteger(ByteArrayOutputStream output, Object value) {
        BigInteger integer = new BigInteger(value.toString());
        if (integer.signum() < 0 || integer.compareTo(MAX_SAFE) > 0) {
            throw new EvidenceContractException("canonicalJsonIntegerOutOfRange");
        }
        output.writeBytes(integer.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeList(ByteArrayOutputStream output, List<?> values) {
        output.write('[');
        for (int index = 0; index < values.size(); index++) {
            writeSeparator(output, index);
            writeValue(output, values.get(index));
        }
        output.write(']');
    }

    private static void writeSeparator(ByteArrayOutputStream output, int index) {
        if (index > 0) {
            output.write(',');
        }
    }

    private static void writeMap(ByteArrayOutputStream output, Map<?, ?> value) {
        List<String> names = stringKeys(value.keySet());
        names.sort(CanonicalJson::compareUtf8);
        output.write('{');
        for (int index = 0; index < names.size(); index++) {
            writeSeparator(output, index);
            String name = names.get(index);
            writeString(output, name);
            output.write(':');
            writeValue(output, value.get(name));
        }
        output.write('}');
    }

    private static List<String> stringKeys(Collection<?> keys) {
        List<String> result = new ArrayList<>();
        for (Object key : keys) {
            if (!(key instanceof String name)) {
                throw new EvidenceContractException("canonicalJsonKeyInvalid");
            }
            validateScalarString(name);
            result.add(name);
        }
        return result;
    }

    static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int shared = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < shared; index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        validateScalarString(value);
        output.write('"');
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            writeCodePoint(output, codePoint);
            offset += Character.charCount(codePoint);
        }
        output.write('"');
    }

    private static void validateScalarString(String value) {
        for (int offset = 0; offset < value.length();) {
            char character = value.charAt(offset);
            if (Character.isHighSurrogate(character)) {
                requireLowSurrogate(value, offset);
                offset += 2;
            } else if (Character.isLowSurrogate(character)) {
                throw new EvidenceContractException("canonicalJsonUnicodeScalarInvalid");
            } else {
                offset++;
            }
        }
    }

    private static void requireLowSurrogate(String value, int offset) {
        if (offset + 1 >= value.length()
                || !Character.isLowSurrogate(value.charAt(offset + 1))) {
            throw new EvidenceContractException("canonicalJsonUnicodeScalarInvalid");
        }
    }

    private static void writeCodePoint(ByteArrayOutputStream output, int codePoint) {
        if (codePoint == '"' || codePoint == '\\') {
            output.write('\\');
            output.write(codePoint);
        } else if (codePoint <= 0x1f) {
            output.writeBytes(String.format("\\u%04x", codePoint)
                    .getBytes(StandardCharsets.US_ASCII));
        } else {
            output.writeBytes(new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Parser {
        private final String text;
        private int offset;

        private Parser(String text) {
            this.text = text;
        }

        private Object parse() {
            skipWhitespace();
            Object result = value();
            skipWhitespace();
            if (offset != text.length()) {
                syntax();
            }
            return result;
        }

        private Object value() {
            if (offset >= text.length()) {
                return syntax();
            }
            char first = text.charAt(offset);
            if (first == '{') {
                return object();
            }
            if (first == '[') {
                return array();
            }
            if (first == '"') {
                return string();
            }
            return primitive(first);
        }

        private Object primitive(char first) {
            return switch (first) {
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            offset++;
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) {
                return result;
            }
            readFields(result);
            return result;
        }

        private void readFields(Map<String, Object> result) {
            while (true) {
                skipWhitespace();
                String name = string();
                if (result.containsKey(name)) {
                    throw new EvidenceContractException("jsonDuplicateKey");
                }
                require(':');
                skipWhitespace();
                result.put(name, value());
                skipWhitespace();
                if (take('}')) {
                    return;
                }
                require(',');
            }
        }

        private List<Object> array() {
            offset++;
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) {
                return result;
            }
            readItems(result);
            return result;
        }

        private void readItems(List<Object> result) {
            while (true) {
                skipWhitespace();
                result.add(value());
                skipWhitespace();
                if (take(']')) {
                    return;
                }
                require(',');
            }
        }

        private String string() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (offset < text.length() && text.charAt(offset) != '"') {
                appendCharacter(result);
            }
            require('"');
            return result.toString();
        }

        private void appendCharacter(StringBuilder result) {
            char character = text.charAt(offset++);
            if (character == '\\') {
                appendEscape(result);
            } else if (character <= 0x1f) {
                syntax();
            } else {
                result.append(character);
            }
        }

        private void appendEscape(StringBuilder result) {
            if (offset >= text.length()) {
                syntax();
            }
            char escaped = text.charAt(offset++);
            if (escaped == 'u') {
                result.append(unicodeEscape());
                return;
            }
            int index = "\"\\/bfnrt".indexOf(escaped);
            if (index < 0) {
                syntax();
            }
            result.append("\"\\/\b\f\n\r\t".charAt(index));
        }

        private char unicodeEscape() {
            if (offset + 4 > text.length()) {
                return syntax();
            }
            String digits = text.substring(offset, offset + 4);
            offset += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException error) {
                throw new EvidenceContractException("jsonSyntaxInvalid", error);
            }
        }

        private Object literal(String token, Object value) {
            if (!text.startsWith(token, offset)) {
                return syntax();
            }
            offset += token.length();
            return value;
        }

        private long number() {
            int start = offset;
            if (take('-') && offset >= text.length()) {
                return integerSyntax();
            }
            readDigits();
            if (offset < text.length() && ".eE".indexOf(text.charAt(offset)) >= 0) {
                return integerSyntax();
            }
            String token = text.substring(start, offset);
            if (!token.matches("-?(?:0|[1-9][0-9]*)") || token.equals("-0")) {
                return integerSyntax();
            }
            return safeInteger(token);
        }

        private void readDigits() {
            int start = offset;
            while (offset < text.length() && Character.isDigit(text.charAt(offset))) {
                offset++;
            }
            if (start == offset) {
                syntax();
            }
        }

        private long safeInteger(String token) {
            try {
                BigInteger value = new BigInteger(token);
                if (value.abs().compareTo(MAX_SAFE) > 0) {
                    throw new EvidenceContractException("jsonIntegerOutOfRange");
                }
                return value.longValueExact();
            } catch (NumberFormatException | ArithmeticException error) {
                throw new EvidenceContractException("jsonIntegerOutOfRange", error);
            }
        }

        private long integerSyntax() {
            throw new EvidenceContractException("jsonIntegerLexemeInvalid");
        }

        private void require(char expected) {
            skipWhitespace();
            if (!take(expected)) {
                syntax();
            }
        }

        private boolean take(char expected) {
            if (offset < text.length() && text.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (offset < text.length() && isWhitespace(text.charAt(offset))) {
                offset++;
            }
        }

        private static boolean isWhitespace(char value) {
            return value == ' ' || value == '\t' || value == '\r' || value == '\n';
        }

        private <T> T syntax() {
            throw new EvidenceContractException("jsonSyntaxInvalid");
        }
    }
}
