package org.json;

import java.util.*;

/** 极简 JSON 解析器（保持对象键的插入顺序） */
class JsonParser {
    private final String s;
    private int i = 0;

    JsonParser(String s) { this.s = s; }

    JSONObject parseValue() { return (JSONObject) value(); }

    private Object value() {
        skipWs();
        char c = s.charAt(i);
        switch (c) {
            case '{': return new JSONObject(object());
            case '[': return new JSONArray(array());
            case '"': return string();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return numberOrLiteral();
        }
    }

    private Object numberOrLiteral() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        if (t.isEmpty()) throw new RuntimeException("bad json at " + i + ": " + s.substring(i, Math.min(i + 20, s.length())));
        if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
        return Integer.parseInt(t);
    }

    private LinkedHashMap<String, Object> object() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        i++; // {
        skipWs();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            skipWs();
            String k = string();
            skipWs();
            if (s.charAt(i) == ':') i++;
            m.put(k, value());
            skipWs();
            char c = s.charAt(i++);
            if (c == '}') return m;
        }
    }

    private ArrayList<Object> array() {
        ArrayList<Object> a = new ArrayList<>();
        i++; // [
        skipWs();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.add(value());
            skipWs();
            char c = s.charAt(i++);
            if (c == ']') return a;
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new RuntimeException("expected string at " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
}
