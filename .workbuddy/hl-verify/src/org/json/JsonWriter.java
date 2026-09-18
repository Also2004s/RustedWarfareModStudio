package org.json;

import java.util.*;

/** 极简 JSON 序列化（保持顺序，正确转义） */
class JsonWriter {
    static String write(Object v, int indent) { return write(v, indent, 0); }

    private static String write(Object v, int indent, int level) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            sb.append('{');
            if (indent > 0) sb.append('\n');
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (n++ > 0) { sb.append(','); if (indent > 0) sb.append('\n'); }
                pad(sb, indent, level + 1);
                sb.append(quote(String.valueOf(e.getKey()))).append(':');
                if (indent > 0) sb.append(' ');
                sb.append(write(e.getValue(), indent, level + 1));
            }
            if (indent > 0 && n > 0) sb.append('\n');
            pad(sb, indent, level);
            sb.append('}');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            sb.append('[');
            if (indent > 0) sb.append('\n');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) { sb.append(','); if (indent > 0) sb.append('\n'); }
                pad(sb, indent, level + 1);
                sb.append(write(l.get(i), indent, level + 1));
            }
            if (indent > 0 && !l.isEmpty()) sb.append('\n');
            pad(sb, indent, level);
            sb.append(']');
        } else if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            sb.append(quote((String) v));
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else {
            sb.append(quote(String.valueOf(v)));
        }
        return sb.toString();
    }

    private static void pad(StringBuilder sb, int indent, int level) {
        if (indent > 0) for (int i = 0; i < indent * level; i++) sb.append(' ');
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
