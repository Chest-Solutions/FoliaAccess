package dev.csl.foliaaccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read a Gradle Module Metadata file.
 * <p>
 * Hand-rolled because this library must not drag in a dependency of its own —
 * the whole point is that it is safe to {@code implementation} into a plugin
 * jar. Objects become {@code Map<String,Object>}, arrays {@code List<Object>},
 * numbers {@code Double}, and everything else the obvious Java type.
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.ws();
        Object value = json.value();
        json.ws();
        if (json.pos < json.src.length()) {
            throw new IllegalArgumentException("trailing JSON at " + json.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    static String string(Object o) {
        return o instanceof String s ? s : null;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> obj() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;                       // {
        ws();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            ws();
            String key = str();
            ws();
            if (src.charAt(pos++) != ':') throw err("expected ':'");
            ws();
            map.put(key, value());
            ws();
            char c = src.charAt(pos++);
            if (c == '}') return map;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> arr() {
        List<Object> list = new ArrayList<>();
        pos++;                       // [
        ws();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = src.charAt(pos++);
            if (c == ']') return list;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String str() {
        if (src.charAt(pos++) != '"') throw err("expected string");
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char esc = src.charAt(pos++);
            switch (esc) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> sb.append(esc);   // " \ / and anything unexpected
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) pos++;
        if (start == pos) throw err("expected value");
        return Double.valueOf(src.substring(start, pos));
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) throw err("expected " + literal);
        pos += literal.length();
    }

    private char peek() {
        if (pos >= src.length()) throw err("unexpected end");
        return src.charAt(pos);
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private IllegalArgumentException err(String message) {
        return new IllegalArgumentException("JSON: " + message + " at " + pos);
    }
}
