package org.json;

import java.util.*;

/** 极简 org.json 兼容实现（仅覆盖项目用到的 API） */
public class JSONArray {
    final ArrayList<Object> list = new ArrayList<>();

    public JSONArray() {}

    JSONArray(ArrayList<Object> l) { list.addAll(l); }

    public JSONArray put(Object v) { list.add(v); return this; }

    public int length() { return list.size(); }
    public Object opt(int i) { return (i >= 0 && i < list.size()) ? list.get(i) : null; }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(int i) { Object v = opt(i); return v instanceof JSONArray ? (JSONArray) v : null; }
    public String optString(int i, String def) { Object v = opt(i); return v instanceof String ? (String) v : def; }
    public String optString(int i) { return optString(i, ""); }

    public String toString() { return JsonWriter.write(list, 0); }
    public String toString(int indent) { return JsonWriter.write(list, indent); }
}
