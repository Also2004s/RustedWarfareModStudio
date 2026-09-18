package com.rwmodstudio.core

/**
 * 序号 / 参数模板键的识别、匹配与实例化（单一来源）。
 *
 * 背景：补全表里存着形如 `builtFrom_{NUM}_name`、`canBuild_#_pos`、`text_{LANG}`、`arm#_[time]`
 * 的条目——花括号 / 方括号 / `#` 是**占位符**（提示语），不是最终写进 ini 的内容；用户实际敲的是
 * 实例化后的键（`builtFrom_1_name`）。本对象负责三件事：
 *
 * 1. **识别**：哪些条目算模板键（[isTemplateKey]，含键形校验，挡掉函数签名 / 变量语法 / 多后缀说明条目）；
 * 2. **匹配**：把用户输入按「占位符位通配」对齐模板（[KeyTemplateIndex.matchPrefix]）；
 * 3. **渲染与反查**：按用户实际填入的内容生成最终键名、未填的占位符留成空位并给出光标偏移
 *    （[KeyTemplateIndex.render]）；反向把实例化键（含空位形态 `builtFrom__name`）还原成模板供查表
 *    （[KeyTemplateIndex.toTemplate]）。
 *
 * 纯字符串工具，不依赖翻译引擎、不碰字典。
 */
object KeyTemplate {

    /** 占位符形态：`{NUM}` / `{LANG}` 等花括号、`[time]` 方括号、`#`。 */
    private val PLACEHOLDER_REGEX =
        Regex("""\{[A-Za-z_][A-Za-z0-9_]*\}|\[[A-Za-z_][A-Za-z0-9_]*\]|#""")

    /**
     * **证据驱动**的 X 形占位符名单（只登记这两条，不做前缀通配）。
     *
     * 数据里含大写 X 的键共 31 条，但只有这 2 条的实例把 X 位换成了数字
     * （`mutatorX_ifUnitWithTags` → 实例 `mutator1_ifUnitWithTags: infantry`）；
     * 其余 29 条的 X 是固定字符——坐标轴（`imageScaleX` / `shadowOffsetX` / `barrelX` /
     * `barrelOffsetX_onOddShots`）、名字里的连接字母（`fireTurretXAtGround*` /
     * `unloadUpToXUnits*`）、或函数参数名（`offsetX` 等，压根不是键），实例里 X 原样保留。
     *
     * 所以这里用显式名单，**不要**改成 `mutatorX_` 前缀通配或 `(?<=[a-z])X(?=_)` 正则：
     * 通配会命中 `barrelOffsetX_onOddShots` 这类坐标轴键，把 X 当序号位替换掉。
     * 判定依据与全量清单见 `docs/模板键覆盖排查-2026-09-17.md`。
     */
    private val X_PLACEHOLDER_KEYS = setOf(
        "mutatorX_ifUnitWithTags",
        "mutatorX_ifUnitWithoutTags"
    )

    /** 名单键里 X 占位符的位置（`mutator` 与 `_` 之间的那个 X）。 */
    private val X_PLACEHOLDER_REGEX = Regex("(?<=mutator)X(?=_)")

    /** 序号位占位符（只允许填数字）。`#`、`{NUM}` 与名单里的 `X` 语义一致。 */
    private val NUMERIC_PLACEHOLDERS = setOf("#", "{NUM}", "X")

    /**
     * 「键形」判据。挡掉的条目：
     * - `defineUnitMemory: unit[] squad, float[] mango` —— 含空格 / 冒号，是函数签名说明
     * - `canBuild_#_name/pos/isLocked` —— 含 `/`，是「请改用 canBuild 部分」的说明性合并条目
     * - `${X}` / `${section.key}` / `%{X}` —— 含 `$` / `%`，是附件表里的变量引用语法
     */
    fun isKeyLike(name: String): Boolean {
        if (name.isEmpty()) return false
        for (c in name) {
            if (c == ' ' || c == '\t' || c == ':' || c == '/' || c == '$' ||
                c == '%' || c == '(' || c == ')' || c == ',' || c == '"'
            ) return false
        }
        return true
    }

    /** 是否为模板键（键形 + 至少含一个占位符；含名单里 X 形占位符的键也算）。 */
    fun isTemplateKey(name: String): Boolean =
        isKeyLike(name) && (PLACEHOLDER_REGEX.containsMatchIn(name) || name in X_PLACEHOLDER_KEYS)

    /** 把键名切成「字面段 / 占位符段」交替序列。X 形占位符只对名单键生效。 */
    internal fun segmentsOf(name: String): List<Seg> {
        val ranges = mutableListOf<IntRange>()
        PLACEHOLDER_REGEX.findAll(name).forEach { ranges.add(it.range) }
        if (name in X_PLACEHOLDER_KEYS) X_PLACEHOLDER_REGEX.findAll(name).forEach { ranges.add(it.range) }
        if (ranges.isEmpty()) return listOf(Seg.Literal(name))
        ranges.sortBy { it.first }
        val out = mutableListOf<Seg>()
        var last = 0
        for (r in ranges) {
            if (r.first < last) continue
            if (r.first > last) out.add(Seg.Literal(name.substring(last, r.first)))
            out.add(Seg.Placeholder(name.substring(r.first, r.last + 1)))
            last = r.last + 1
        }
        if (last < name.length) out.add(Seg.Literal(name.substring(last)))
        return out
    }

    /** 占位符位允许填入的字符：序号位只允许数字；语言 / 时间位允许字母数字下划线。 */
    internal fun isFillCharAllowed(raw: String, c: Char): Boolean =
        if (raw in NUMERIC_PLACEHOLDERS) c.isDigit() else (c.isLetterOrDigit() || c == '_')

    /** 单个占位符位最多消耗的字符数（防病态回溯）。 */
    internal fun maxFillLen(raw: String): Int = if (raw in NUMERIC_PLACEHOLDERS) 4 else 16
}

/**
 * 全局模板索引注册表。值补全侧（`ValueCompletionRequest` / `CompletionShared`）需要把实例化键
 * （如 `builtFrom_1_name`）还原成模板名去查类型，但它拿不到补全表；由补全聚合器在构造时注册一次，
 * 之后双方共用同一份模板集。注册是幂等的，空集合不覆盖已有索引。
 */
object KeyTemplateRegistry {

    @Volatile
    private var index: KeyTemplateIndex = KeyTemplateIndex(emptyList())

    /** 用补全表键名注册模板集（通常在补全聚合器构造时调用）。 */
    fun register(names: Collection<String>) {
        val next = KeyTemplateIndex(names)
        if (!next.isEmpty) index = next
    }

    /** 实例化键（含空位形态 `builtFrom__name`）→ 模板原文；未命中返回 null。 */
    fun toTemplate(key: String): String? = index.toTemplate(key)

    /** 实例化键 → 模板原文，未命中时原样返回（便于直接参与字符串比较）。 */
    fun normalize(key: String): String = toTemplate(key) ?: key
}

/** 模板键的分段单元。 */
sealed interface Seg {
    /** 固定字面（参与逐字符比对） */
    data class Literal(val text: String) : Seg
    /** 占位符（原样保留，如 `{NUM}` / `#` / `[time]` / `{LANG}`） */
    data class Placeholder(val raw: String) : Seg
}

/**
 * 模板键索引。构造时从补全表键名里筛出模板键并预编译分段，之后匹配/反查都是纯内存计算，
 * 不在补全热路径做扫描。
 */
class KeyTemplateIndex(names: Collection<String>) {

    class Template(val source: String, internal val segments: List<Seg>)

    /** 一次前缀匹配结果：命中模板 + 各占位符位用户实际填入的内容（与模板占位符顺序一一对应）。 */
    data class Hit(val template: Template, val fills: List<String>)

    /** 渲染结果：最终键名 + 光标应停留的列偏移（相对键名起点；null 表示所有占位符都填满了，光标落行尾）。 */
    data class Rendered(val key: String, val caretOffset: Int?)

    private val templates: List<Template> = names
        .asSequence()
        .filter { KeyTemplate.isTemplateKey(it) }
        .distinct()
        .map { Template(it, KeyTemplate.segmentsOf(it)) }
        .toList()

    private val bySource: Map<String, Template> = templates.associateBy { it.source }

    val isEmpty: Boolean get() = templates.isEmpty()

    /** 按模板原文取模板（找不到返回 null）。 */
    fun templateByName(source: String): Template? = bySource[source]

    /** 用户输入前缀对模板做「占位符位通配」匹配；字面段中途结束也算命中（前缀命中）。 */
    fun matchPrefix(prefix: String): List<Hit> {
        if (prefix.isEmpty() || templates.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        for (t in templates) {
            val fills = matchSegments(t.segments, 0, prefix, 0, ArrayList(), allowPartialLiteral = true)
            if (fills != null) out.add(Hit(t, fills))
        }
        return out
    }

    /**
     * 实例化键 → 模板名（要求完整消费，字面段中途结束不算）。
     * 兼容「空位形态」：`builtFrom__name` 也能还原成 `builtFrom_{NUM}_name`（供值补全查类型）。
     * 多个命中时取最长的模板（更具体的优先）。
     */
    fun toTemplate(key: String): String? {
        if (key.isEmpty() || templates.isEmpty()) return null
        var best: String? = null
        for (t in templates) {
            if (matchSegments(t.segments, 0, key, 0, ArrayList(), allowPartialLiteral = false) == null) continue
            if (best == null || t.source.length > best!!.length) best = t.source
        }
        return best
    }

    /**
     * 按填入内容渲染最终键名。占位符有值则原样替换；无值则**原地留空**（两侧原字符保留，
     * 如 `builtFrom_{NUM}_name` → `builtFrom__name`），并把光标偏移指向最靠前的空位。
     */
    fun render(template: Template, fills: List<String>): Rendered {
        val sb = StringBuilder()
        var caret: Int? = null
        var slot = 0
        for (seg in template.segments) {
            when (seg) {
                is Seg.Literal -> sb.append(seg.text)
                is Seg.Placeholder -> {
                    val fill = fills.getOrNull(slot).orEmpty()
                    if (fill.isEmpty()) {
                        if (caret == null) caret = sb.length
                    } else {
                        sb.append(fill)
                    }
                    slot++
                }
            }
        }
        return Rendered(sb.toString(), caret)
    }

    /**
     * 分段匹配。返回各占位符位的填入内容；不匹配返回 null。
     * - [allowPartialLiteral] = true：字面段中途前缀结束 → 视为命中（前缀补全场景）
     * - [allowPartialLiteral] = false：必须完整消费输入（反查模板场景）
     */
    private fun matchSegments(
        segs: List<Seg>,
        index: Int,
        prefix: String,
        pos: Int,
        fills: MutableList<String>,
        allowPartialLiteral: Boolean
    ): List<String>? {
        var si = index
        var p = pos
        while (true) {
            if (si >= segs.size) {
                // 模板走完：输入也必须刚好用完，否则视为「用户输入比模板更长」→ 不命中
                return if (p == prefix.length) ArrayList(fills) else null
            }
            when (val seg = segs[si]) {
                is Seg.Literal -> {
                    for (ch in seg.text) {
                        if (p >= prefix.length) {
                            return if (allowPartialLiteral) ArrayList(fills) else null
                        }
                        if (!prefix[p].equals(ch, ignoreCase = true)) return null
                        p++
                    }
                    si++
                }
                is Seg.Placeholder -> {
                    val maxLen = minOf(prefix.length - p, KeyTemplate.maxFillLen(seg.raw))
                    var len = 0
                    while (len <= maxLen) {
                        if (len > 0 && !KeyTemplate.isFillCharAllowed(seg.raw, prefix[p + len - 1])) break
                        fills.add(prefix.substring(p, p + len))
                        val tail = matchSegments(segs, si + 1, prefix, p + len, fills, allowPartialLiteral)
                        fills.removeAt(fills.size - 1)
                        if (tail != null) return tail
                        len++
                    }
                    return null
                }
            }
        }
    }
}
