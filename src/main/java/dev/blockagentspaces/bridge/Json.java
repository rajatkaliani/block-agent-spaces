package dev.blockagentspaces.bridge;

import java.util.*;
import java.util.regex.*;

/** Small dependency-free JSON helper for the deliberately flat v1 bridge payloads. */
final class Json {
    private Json() { }
    static String string(String body, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(body);
        return m.find() ? unescape(m.group(1)) : "";
    }
    static List<String> strings(String body, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\[([^]]*)]").matcher(body);
        if (!m.find()) return List.of();
        Matcher values = Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(m.group(1));
        List<String> result = new ArrayList<>();
        while (values.find()) result.add(unescape(values.group(1)));
        return result;
    }
    static long longValue(String body, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)").matcher(body);
        if (!m.find()) throw new IllegalArgumentException(key + " must be an integer");
        try { return Long.parseLong(m.group(1)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be an integer"); }
    }
    static String quote(String value) { return "\"" + escape(value) + "\""; }
    static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    static String unescape(String value) { return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\"); }
}
