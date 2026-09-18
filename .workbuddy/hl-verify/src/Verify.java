import org.json.JSONArray;
import org.json.JSONObject;
import org.joni.*;
import org.jcodings.specific.UTF8Encoding;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 端到端对账：用**编译产物里的真实生产代码**生成「默认规则 → 合并语法」，
 * 再逐条与内置语法比对（含捕获组），最后用真实引擎 joni 在语料上验证命中是否等价。
 */
public class Verify {

    static final List<String> CORPUS = Arrays.asList(
        "# 这是一行注释",
        "; 分号注释",
        "[core]",
        "[core_1]",
        "[attachment_炮塔]",
        "[graphics]",
        "name: 中文单位名",
        "maxHp: 100",
        "price: 100,200",
        "displayText: 生命值",
        "autoTrigger: if 自身有标签",
        "也执行动作:控制距离, 撤退",
        "也执行动作:撤退",
        "条件: 自身在移动 and 自身有标签",
        "条件:自身资源大于(100)",
        "检查自身有活动的路径点吗",
        "移动速度: 0.5",
        "maxAttackRange: 距离 10",
        "价值: 距离方差(自身位置, 当前动作目标)",
        "目标: .距离(10)",
        "偏移: .获取相对偏移(1, 2)",
        "标记: 创建标记(10, 20)",
        "判定: 选择(1) 创建标记(2)",
        "bool: true",
        "bool: false",
        "bool: 真",
        "bool: 无",
        "bool: 满",
        "bool: 空的",
        "team: own",
        "team: 己方",
        "team: guard",
        "movement: LAND",
        "movement: 空中",
        "movement: OVER_CLIFF_WATER",
        "frame: 每帧",
        "frame: every8Frames",
        "rate: 1.5s",
        "rate: 200ms",
        "color: #FF00FF88",
        "text: \"双引号字符串\"",
        "text: '单引号字符串'",
        "expr: a==b",
        "expr: a<=b",
        "expr: a!=b",
        "expr: x=1",
        "expr: a+b-c*d/e%f",
        "expr: if a and not b or c",
        "type: float",
        "type: boolean",
        "ref: self",
        "ref: 当前动作目标",
        "ref: 空单位",
        "ref: 自身资源",
        "ref: 存活时间",
        "@memory hp: float[10]",
        "@memory2 变量",
        "path: ROOT:units/land.ini",
        "path: CUSTOM:x",
        "arg: 有标签==xxx",
        "arg: id=1",
        "arg: 范围内==5",
        "value: 移动速度",
        "value: 控制距离",
        "value: 接近距离",
        "notify: 距离太远了",
        "list: 撤退, 控制距离, 接近距离",
        "empty:",
        "weird: 自身能量空 or 自身能量满",
        "num: 123",
        "num: 0.25",
        "call: squareRoot(9)",
        "call: 读取单位内存(1)"
    );

    static final List<String> REAL = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String grammarPath = args.length > 0 ? args[0] : "app/src/main/assets/textmate/ini/ini.tmLanguage.json";
        java.io.File real = new java.io.File("corpus_all.txt");
        if (real.exists()) {
            Set<String> seen = new LinkedHashSet<>(CORPUS);
            String raw = new String(java.nio.file.Files.readAllBytes(real.toPath()), StandardCharsets.UTF_8);
            raw = raw.replace("\r\n", "\n").replace('\r', '\n');
            for (String l : raw.split("\n")) {
                if (l.strip().length() > 0 && seen.add(l.strip())) REAL.add(l.strip());
            }
            System.out.println("真实语料: 唯一行 " + REAL.size() + "（另有人造样本 " + CORPUS.size() + " 行）");
        }
        String base = new String(Files.readAllBytes(Paths.get(grammarPath)), StandardCharsets.UTF_8);

        Class<?> defCls = Class.forName("com.rwmodstudio.core.HighlightRulesDefaults");
        Object def = defCls.getField("INSTANCE").get(null);
        Class<?> mgrCls2 = Class.forName("com.rwmodstudio.core.HighlightRuleManager");
        Object mgr2 = mgrCls2.getField("INSTANCE").get(null);
        List<Object> cats;
        if (args.length > 1) {
            // 用设备上的真实规则文件（走生产代码的 parseRules 回读路径）
            String rulesText = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
            Method pr = mgrCls2.getMethod("parseRules$app_debug", String.class);
            @SuppressWarnings("unchecked")
            List<Object> parsed = (List<Object>) pr.invoke(mgr2, rulesText);
            cats = parsed;
            System.out.println("（规则来源: 设备文件 " + args[1] + "）");
        } else {
            Method parse = defCls.getMethod("parseBuiltinRules", String.class);
            @SuppressWarnings("unchecked")
            List<Object> parsed = (List<Object>) parse.invoke(def, base);
            cats = parsed;
            System.out.println("（规则来源: 内置复刻 defaults）");
        }

        int ruleCount = 0, kw = 0, rx = 0;
        Set<String> allKeys = new LinkedHashSet<>();
        Set<String> kwKeys = new LinkedHashSet<>();
        for (Object c : cats) {
            Object rules = getter(c, "getRules");
            int n = ((List<?>) rules).size();
            ruleCount += n;
            for (Object r : (List<?>) rules) {
                Object t = getter(r, "getType");
                String src = String.valueOf(getter(r, "getSource"));
                Object inst = getter(r, "getSourceInstance");
                if (src != null && !src.isEmpty() && !"null".equals(src)) {
                    allKeys.add(src + " " + inst);
                    if (!String.valueOf(t).contains("REGEX")) kwKeys.add(src + " " + inst);
                }
                if (String.valueOf(t).contains("REGEX")) rx++; else kw++;
            }
        }
        System.out.println("规则文件里的 (内置原文,份号) 键: " + allKeys.size() + "（其中关键字类 " + kwKeys.size() + "）");
        System.out.println("默认规则: 分组=" + cats.size() + " 规则=" + ruleCount + " (关键字 " + kw + " / 正则 " + rx + ")");

        Method parity = defCls.getMethod("parityReport", String.class, List.class);
        Object report = parity.invoke(def, base, cats);
        System.out.println("复刻对账: " + report);

        Class<?> mgrCls = Class.forName("com.rwmodstudio.core.HighlightRuleManager");
        Object mgr = mgrCls.getField("INSTANCE").get(null);
        Method raw = mgrCls.getMethod("buildMergedGrammarRaw$app_debug", String.class, List.class);
        Object triple = raw.invoke(mgr, base, cats);
        String merged = String.valueOf(getter(triple, "getFirst"));
        Object replaced = getter(triple, "getSecond");
        Object added = getter(triple, "getThird");
        System.out.println("合并: 原位替换=" + replaced + " 新增手写规则=" + added);

        List<Rule> baseRules = new ArrayList<>();
        List<Rule> mergedRules = new ArrayList<>();
        collectRules(new JSONObject(base), baseRules);
        collectRules(new JSONObject(merged), mergedRules);
        System.out.println("规则正文条数: 内置=" + baseRules.size() + " 合并后=" + mergedRules.size());

        int diffs = 0, semDiff = 0, replacedWithCaptures = 0;
        for (int i = 0; i < Math.max(baseRules.size(), mergedRules.size()); i++) {
            Rule br = i < baseRules.size() ? baseRules.get(i) : null;
            Rule mr = i < mergedRules.size() ? mergedRules.get(i) : null;
            if (br == null || mr == null) { System.out.println("--- 结构错位 #" + i); continue; }
            if (br.body.equals(mr.body)) {
                // 带 scope 的规则（规则级 name 或 captures 具名）本该被复刻规则替换，没换 = 定位失败
                if (br.colored) {
                    System.out.println("\n--- 未替换的配色规则 #" + i + " (" + br.scope + ") ---");
                    System.out.println("  " + clip(br.body));
                }
                continue;
            }
            diffs++;
            if (br.hasCaptures) replacedWithCaptures++;
            List<String> bad = semanticDiff(br.body, mr.body, br.hasCaptures);
            if (bad.isEmpty()) continue;
            semDiff++;
            System.out.println("\n--- 语义差异 #" + i + " (" + br.scope + ") ---");
            System.out.println("  内置: " + clip(br.body));
            System.out.println("  合并: " + clip(mr.body));
            for (String s : bad) System.out.println("      " + s);
        }
        // 对照实验：把合并结果还原成「改前」的 ASCII 边界，看同批语料能不能抓出差异（验证语料区分力）
        int ctrl = 0, ctrlBad = 0;
        for (int i = 0; i < Math.min(baseRules.size(), mergedRules.size()); i++) {
            Rule br = baseRules.get(i), mr = mergedRules.get(i);
            if (br.body.equals(mr.body)) continue;
            ctrl++;
            if (!semanticDiff(br.body, degradeToAsciiBoundary(mr.body), br.hasCaptures).isEmpty()) ctrlBad++;
        }
        System.out.println("对照（把合并结果退化成改前的 ASCII 边界）: 被替换 " + ctrl + " 条，语料上不一致 " + ctrlBad + " 条"
                + " —— 不一致>0 才说明这批语料有区分力");
        System.out.println("\n被替换的规则里带 captures 的条数: " + replacedWithCaptures
                + "（>0 就要小心捕获组编号挪位）");
        System.out.println("\n==== 结论: 被替换=" + diffs + " 条，其中语料上语义不一致 " + semDiff + " 条 ====");

        // 复刻端的 (原文,份号) 键 vs 合并端扫描算出的 (原文,份号) 键 → 差集 = 定位失败（改了也不生效）
        Set<String> mergeSide = mergeSideKeys(base);
        System.out.println("\n==== 定位对账: 复刻端键 " + allKeys.size() + " / 合并端算出的键 " + mergeSide.size()
                + "（去重） ====");
        List<String> miss = new ArrayList<>();
        for (String k : allKeys) if (!mergeSide.contains(k)) miss.add(k);
        if (!miss.isEmpty()) {
            String a = miss.get(0);
            System.out.println("  调试·复刻端键样例: [" + a.substring(Math.max(0, a.length() - 12)) + "] 尾部 / 长度=" + a.length());
            String b = mergeSide.iterator().next();
            System.out.println("  调试·合并端键样例: [" + b.substring(Math.max(0, b.length() - 12)) + "] 尾部 / 长度=" + b.length());
            System.out.println("  调试·复刻端首个原文: " + clip(a));
            System.out.println("  调试·合并端首个原文: " + clip(b));
        }
        System.out.println("复刻端有、合并端算不出（这些组改了不生效）: " + miss.size() + " 个");
        Map<String, String> groupOfKey = new LinkedHashMap<>();
        for (Object c : cats) {
            String lname = String.valueOf(getter(c, "getCategoryId")) + "/" + String.valueOf(getter(c, "getListName"));
            for (Object r : (List<?>) getter(c, "getRules")) {
                String src = String.valueOf(getter(r, "getSource"));
                Object inst = getter(r, "getSourceInstance");
                if (src != null && !src.isEmpty() && !"null".equals(src)) groupOfKey.put(src + " " + inst, lname);
            }
        }
        for (String k : miss) {
            int cut = k.lastIndexOf(' ');
            String bodyKey = cut > 0 ? k.substring(0, cut) : k;
            System.out.println("  ✗ " + groupOfKey.get(k) + "  份号=" + (cut > 0 ? k.substring(cut + 1) : "?")
                    + "  原文=" + clip(bodyKey));
        }
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("keys_replica.txt"),
                    allKeys.stream().map(k -> k.replace("\n", "\\n")).collect(java.util.stream.Collectors.toList()),
                    StandardCharsets.UTF_8);
            java.nio.file.Files.write(java.nio.file.Paths.get("keys_merge.txt"),
                    mergeSide.stream().map(k -> k.replace("\n", "\\n")).collect(java.util.stream.Collectors.toList()),
                    StandardCharsets.UTF_8);
        } catch (Exception e) { e.printStackTrace(); }
    }

    /** 复刻生产代码 buildMergedGrammarRaw 的扫描逻辑，算出合并端眼中的 (正文, 份号) 键集合 */
    static Set<String> mergeSideKeys(String json) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        int i = 0;
        while (i < json.length()) {
            if (json.startsWith("\"match\"", i) || json.startsWith("\"begin\"", i)) {
                int[] span = stringValueSpan(json, i);
                if (span != null) {
                    String body = decode(json.substring(span[0] + 1, span[1]));
                    String scope = enclosingScopeOf(json, i);
                    int inst = -1;
                    if (scope != null) {
                        int n = seen.getOrDefault(scope, 0);
                        seen.put(scope, n + 1);
                        inst = n;
                    }
                    out.add(body + " " + inst);
                }
                i += 8;
            } else {
                i++;
            }
        }
        return out;
    }

    static int[] stringValueSpan(String json, int keyIdx) {
        int colon = json.indexOf(':', keyIdx);
        if (colon < 0) return null;
        int quote = -1;
        int j = colon + 1;
        while (j < json.length() && j < colon + 40) {
            char c = json.charAt(j);
            if (c == '"') { quote = j; break; }
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return null;
            j++;
        }
        if (quote < 0) return null;
        int i = quote + 1;
        boolean escaped = false;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') return new int[]{quote, i};
            i++;
        }
        return null;
    }

    static String enclosingScopeOf(String json, int idx) {
        int depth = 0;
        int i = idx;
        while (i >= 0) {
            char c = json.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth == 0) {
                    int objEnd = endOfObjectAt(json, i);
                    String obj = json.substring(i, Math.min(objEnd, json.length()));
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(obj);
                    return m.find() ? m.group(1) : null;
                }
                depth--;
            }
            i--;
        }
        return null;
    }

    static int endOfObjectAt(String json, int openIdx) {
        int depth = 0;
        int i = openIdx;
        boolean inString = false, escaped = false;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) return i; }
            }
            i++;
        }
        return json.length();
    }

    static String decode(String s) {
        if (!s.contains("\\")) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                            i += 6;
                            continue;
                        }
                        sb.append(n);
                        break;
                    default: sb.append(n);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }


    /** 把 (?:a|b) 退化成 (?:(?<![A-Za-z0-9_])a(?![A-Za-z0-9_])|...) —— 即「改前」的写法 */
    static String degradeToAsciiBoundary(String body) {
        if (!(body.startsWith("(?:") && body.endsWith(")"))) return body;
        String[] parts = body.substring(3, body.length() - 1).split("\\|");
        StringBuilder sb = new StringBuilder("(?:");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("\\b")) p = p.substring(2);
            if (p.endsWith("\\b")) p = p.substring(0, p.length() - 2);
            if (i > 0) sb.append("|");
            sb.append("(?<![A-Za-z0-9_])").append(p).append("(?![A-Za-z0-9_])");
        }
        return sb.append(")").toString();
    }

    static class Rule {
        String body; String scope = "-"; boolean colored; boolean hasCaptures;
        Rule(String body) { this.body = body; }
    }

    static void collectRules(Object node, List<Rule> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object body = o.opt("match");
            if (body == null) body = o.opt("begin");
            boolean hasCap = false;
            for (String k : new String[]{"captures", "beginCaptures", "endCaptures"}) {
                if (o.opt(k) instanceof JSONObject) hasCap = true;
            }
            Object name = o.opt("name");
            if (body instanceof String) {
                Rule r = new Rule((String) body);
                r.scope = name instanceof String ? (String) name : (hasCap ? "(captures)" : "-");
                r.colored = (name instanceof String) || hasCap;
                r.hasCaptures = hasCap;
                out.add(r);
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) collectRules(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectRules(a.opt(i), out);
        }
    }

    static Object getter(Object o, String name) throws Exception {
        Method m = o.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    static String clip(String s) { return s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    static void collectBodies(Object node, List<String> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object body = o.opt("match");
            if (body == null) body = o.opt("begin");
            if (body instanceof String) out.add((String) body);
            Iterator<String> it = o.keys();
            while (it.hasNext()) collectBodies(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectBodies(a.opt(i), out);
        }
    }

    /**
     * 用真实引擎比较两条正则在语料上的命中，返回不一致描述。
     * 只有规则**带 captures** 时捕获组编号才影响着色（captures 按组号上色）；
     * 不带 captures 的规则，内置 `(a|b)` 多出来的那 1 个未命名捕获组不参与上色 → 只比整体命中。
     */
    static List<String> semanticDiff(String a, String b, boolean withCaptures) {
        List<String> bad = new ArrayList<>();
        for (String line : LINES()) {
            String ra = hits(a, line, withCaptures);
            String rb = hits(b, line, withCaptures);
            if (!ra.equals(rb)) {
                bad.add("文本「" + line + "」\n          内置=" + ra + "\n          合并=" + rb);
            }
        }
        return bad;
    }

    static List<String> LINES() {
        List<String> all = new ArrayList<>(CORPUS);
        all.addAll(REAL);
        return all;
    }

    static final Map<String, Regex> COMPILED = new HashMap<>();

    /** 返回该正则在文本里的全部命中，用于精确比较 */
    static String hits(String pattern, String text, boolean withCaptures) {
        StringBuilder sb = new StringBuilder();
        try {
            Regex regex = COMPILED.computeIfAbsent(pattern, k -> {
                byte[] kp = k.getBytes(StandardCharsets.UTF_8);
                return new Regex(kp, 0, kp.length, Option.NONE, UTF8Encoding.INSTANCE, Syntax.DEFAULT);
            });
            byte[] t = text.getBytes(StandardCharsets.UTF_8);
            Matcher m = regex.matcher(t);
            int from = 0;
            while (from <= t.length) {
                int r = m.search(from, t.length, Option.NONE);
                if (r < 0) break;
                Region reg = m.getEagerRegion();
                int n = (reg == null || !withCaptures) ? 1 : reg.getNumRegs();
                sb.append("[");
                for (int g = 0; g < n; g++) {
                    int s = reg == null ? -1 : reg.getBeg(g), e = reg == null ? -1 : reg.getEnd(g);
                    if (s < 0 || e < 0) { sb.append("-"); }
                    else {
                        sb.append(new String(t, s, e - s, StandardCharsets.UTF_8));
                    }
                    if (g < n - 1) sb.append("|");
                }
                sb.append("]");
                int e0 = reg.getEnd(0);
                from = (e0 > reg.getBeg(0)) ? e0 : reg.getBeg(0) + 1;
            }
        } catch (Throwable th) {
            sb.append("<ERR ").append(th).append(">");
        }
        return sb.toString();
    }
}
