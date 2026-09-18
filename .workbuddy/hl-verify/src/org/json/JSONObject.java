package org.json;

import java.util.*;

/** 极简 org.json 兼容实现（仅覆盖项目用到的 API，供 JVM 离线验证用） */
public class JSONObject {
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {}

    public JSONObject(String json) {
        this(new JsonParser(json).parseValue().map);
    }

    JSONObject(LinkedHashMap<String, Object> m) { map.putAll(m); }

    public JSONObject put(String k, Object v) { map.put(k, v); return this; }
    public JSONObject put(String k, int v) { map.put(k, v); return this; }
    public JSONObject put(String k, boolean v) { map.put(k, v); return this; }
    public JSONObject put(String k, long v) { map.put(k, v); return this; }
    public JSONObject put(String k, double v) { map.put(k, v); return this; }

    public Object opt(String k) { return map.get(k); }
    public boolean has(String k) { return map.containsKey(k); }
    public int length() { return map.size(); }
    public Iterator<String> keys() { return map.keySet().iterator(); }

    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String def) {
        Object v = map.get(k);
        return v instanceof String ? (String) v : def;
    }
    public int optInt(String k, int def) {
        Object v = map.get(k);
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) { try { return Integer.parseInt((String) v); } catch (Exception e) {} }
        return def;
    }
    public boolean optBoolean(String k, boolean def) {
        Object v = map.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }
    public JSONObject optJSONObject(String k) {
        Object v = map.get(k);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }
    public JSONArray optJSONArray(String k) {
        Object v = map.get(k);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public String toString() { return JsonWriter.write(map, 0); }
    public String toString(int indent) { return JsonWriter.write(map, indent); }
}
