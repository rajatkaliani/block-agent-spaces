package dev.blockagentspaces.bridge;

import java.util.*;

/** Small dependency-free parser for deliberately flat bridge request payloads. */
final class Json {
    private Json() { }
    static Map<String, Object> object(String body) {
        return new Parser(body).object();
    }
    static String requiredString(Map<String, Object> object, String key, int maxLength) {
        if (!object.containsKey(key)) throw new IllegalArgumentException(key + " is required");
        return string(object, key, maxLength, false);
    }
    static String optionalString(Map<String, Object> object, String key, int maxLength) {
        return object.containsKey(key) ? string(object, key, maxLength, true) : "";
    }
    static List<String> optionalStrings(Map<String, Object> object, String key, int maxItems, int maxItemLength) {
        if (!object.containsKey(key)) return List.of();
        Object raw = object.get(key);
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException(key + " must be an array of strings");
        if (values.size() > maxItems) throw new IllegalArgumentException(key + " has too many entries");
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be an array of strings");
            result.add(validateText(text, key, maxItemLength, false));
        }
        return List.copyOf(result);
    }
    static long requiredLong(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Long number)) throw new IllegalArgumentException(key + " must be an integer");
        return number;
    }
    static String quote(String value) { return "\"" + escape(value) + "\""; }
    static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"); }

    private static String string(Map<String, Object> object, String key, int maxLength, boolean allowBlank) {
        Object value = object.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return validateText(text, key, maxLength, allowBlank);
    }
    private static String validateText(String text, String key, int maxLength, boolean allowBlank) {
        if (text.length() > maxLength) throw new IllegalArgumentException(key + " exceeds " + maxLength + " characters");
        if (!allowBlank && text.isBlank()) throw new IllegalArgumentException(key + " is required");
        if (text.chars().anyMatch(character -> character == 0 || (Character.isISOControl(character) && character != '\n' && character != '\t'))) {
            throw new IllegalArgumentException(key + " contains unsupported control characters");
        }
        return text;
    }

    private static final class Parser {
        private final String source;
        private int cursor;
        Parser(String source) {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("request body must be a JSON object");
            this.source = source;
        }
        Map<String, Object> object() {
            whitespace(); expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) { end(); return result; }
            while (true) {
                whitespace(); String key = string(); whitespace(); expect(':'); whitespace();
                if (result.putIfAbsent(key, value()) != null) throw new IllegalArgumentException("duplicate key: " + key);
                whitespace();
                if (take('}')) { end(); return result; }
                expect(',');
            }
        }
        private Object value() {
            if (peek('"')) return string();
            if (peek('[')) return stringArray();
            if (cursor < source.length() && (source.charAt(cursor) == '-' || Character.isDigit(source.charAt(cursor)))) return number();
            throw new IllegalArgumentException("unsupported JSON value");
        }
        private List<String> stringArray() {
            expect('['); whitespace(); List<String> result = new ArrayList<>();
            if (take(']')) return result;
            while (true) {
                whitespace(); if (!peek('"')) throw new IllegalArgumentException("arrays may contain only strings");
                result.add(string()); whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }
        private Long number() {
            int start = cursor;
            if (take('-') && (cursor == source.length() || !Character.isDigit(source.charAt(cursor)))) throw new IllegalArgumentException("invalid integer");
            while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) cursor++;
            try { return Long.parseLong(source.substring(start, cursor)); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("integer is out of range"); }
        }
        private String string() {
            expect('"'); StringBuilder result = new StringBuilder();
            while (cursor < source.length()) {
                char current = source.charAt(cursor++);
                if (current == '"') return result.toString();
                if (current < 0x20) throw new IllegalArgumentException("unescaped control character in string");
                if (current != '\\') { result.append(current); continue; }
                if (cursor == source.length()) throw new IllegalArgumentException("unterminated escape sequence");
                char escaped = source.charAt(cursor++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b'); case 'f' -> result.append('\f'); case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                    case 'u' -> result.append(unicode());
                    default -> throw new IllegalArgumentException("invalid escape sequence");
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }
        private char unicode() {
            if (cursor + 4 > source.length()) throw new IllegalArgumentException("invalid unicode escape");
            String hex = source.substring(cursor, cursor + 4); cursor += 4;
            try { return (char) Integer.parseInt(hex, 16); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid unicode escape"); }
        }
        private void end() { whitespace(); if (cursor != source.length()) throw new IllegalArgumentException("unexpected trailing data"); }
        private void whitespace() { while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++; }
        private void expect(char expected) { if (!take(expected)) throw new IllegalArgumentException("malformed JSON"); }
        private boolean take(char expected) { if (cursor < source.length() && source.charAt(cursor) == expected) { cursor++; return true; } return false; }
        private boolean peek(char expected) { return cursor < source.length() && source.charAt(cursor) == expected; }
    }
}
