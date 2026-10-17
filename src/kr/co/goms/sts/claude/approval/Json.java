package kr.co.goms.sts.claude.approval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny dependency-free JSON reader/writer (objects -> LinkedHashMap, arrays -> ArrayList). */
public final class Json {
    private final String s;
    private int pos;

    private Json(String s) { this.s = s; }

    /** Returns {@code o} as a JSON object map, or an empty map when it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.s.length()) throw p.err("Trailing characters");
        return v;
    }

    private Object value() {
        ws();
        if (pos >= s.length()) throw err("Unexpected end");
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++;
        ws();
        if (peek('}')) { pos++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (!peek(':')) throw err("Expected ':'");
            pos++;
            m.put(k, value());
            ws();
            if (peek(',')) { pos++; continue; }
            if (peek('}')) { pos++; return m; }
            throw err("Expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        pos++;
        ws();
        if (peek(']')) { pos++; return l; }
        while (true) {
            l.add(value());
            ws();
            if (peek(',')) { pos++; continue; }
            if (peek(']')) { pos++; return l; }
            throw err("Expected ',' or ']'");
        }
    }

    private String string() {
        if (!peek('"')) throw err("Expected string");
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (pos >= s.length()) break;
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) throw err("Bad unicode escape");
                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw err("Bad escape");
            }
        }
        throw err("Unterminated string");
    }

    private Object number() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
        String n = s.substring(start, pos);
        if (n.isEmpty()) throw err("Unexpected character '" + s.charAt(pos) + "'");
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        long l = Long.parseLong(n);
        return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Object) (int) l : (Object) l;
    }

    private void expect(String word) {
        if (!s.startsWith(word, pos)) throw err("Expected " + word);
        pos += word.length();
    }

    private boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }

    private void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

    private RuntimeException err(String msg) { return new IllegalArgumentException(msg + " at " + pos); }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) sb.append("null");
        else if (v instanceof String str) quote(str, sb);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) sb.append(v);
        else if (v instanceof Double d) sb.append(d.isNaN() || d.isInfinite() ? "null" : (d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : d.toString()));
        else if (v instanceof Number num) sb.append(num);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) { if (!first) sb.append(','); first = false; write(o, sb); }
            sb.append(']');
        } else quote(String.valueOf(v), sb);
    }

    private static void quote(String str, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }
}
