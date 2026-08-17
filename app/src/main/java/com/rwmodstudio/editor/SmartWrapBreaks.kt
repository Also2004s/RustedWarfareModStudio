package com.rwmodstudio.editor

import java.util.regex.Pattern

/**
 * 按优先级分组的智能换行断点（纯函数，可单测）。
 *
 * 优先级（布局层按顺序选择断点，低数值 = 高优先级）：
 * - 硬断（布局层处理，不在此类中）：字面 `\n` —— 本行绝不能越过它，直接断行；
 * - [parenGroups]：含 `and`/`or` 的 `( ... )` 组（`(xxx or xxx)` 整组独占一行起）；
 * - [andOr]：` and `/` or ` 操作符；
 * - [operators]：运算符号 `+`/`-`（其后是字母含汉字/下划线）、`*`（两侧非空白）；
 * - [commas]：`,`；
 * - 兜底（布局层 computeFallbackBreaks）：空格与其余标点。
 *
 * 断行风格：符号行首；中文标识符（如 `+盟友有此单位数量(`）由 Character.isLetter() 天然覆盖；
 * 汉字不拆分由布局层兜底保证。
 */
class PriorityBreaks(
    val parenGroups: IntArray,
    val andOr: IntArray,
    val operators: IntArray,
    val commas: IntArray
)

/**
 * 智能换行断点计算。
 *
 * 规则（仅影响显示，不改动文本内容）：
 * - R1：字面 `\n`（反斜杠 + n）之前断行，`\n` 显示在新行行首（硬断，布局层强制本行不越过）；
 * - R2：` and `/` or `（大小写不敏感、单词边界）之前断行，任意嵌套深度；
 * - R3：含 ` and `/` or ` 的 `( ... )` 组之前断行；
 * - R4：`+`/`-` 之前断行，仅当其后的操作数首字符是字母（含汉字）或下划线；
 * - R5：`,` 之前断行（任意深度）；
 * - R6：`*` 之前断行（`*` 两侧非空白）。
 */
object SmartWrapBreaks {

    private val AND_OR = Pattern.compile("\\band\\b|\\bor\\b", Pattern.CASE_INSENSITIVE)

    /** 兜底断点可用的符号集合（`=` 不在此列，见 [computeFallbackBreaks]）。 */
    private val FALLBACK_SYMBOLS = setOf(',', '*', '+', '-', '%', '/', ':', '(', ' ')

    /**
     * 按优先级分组：parenGroups = R3，andOr = R2，operators = R4/R6，commas = R5。
     * 各组内升序、剔除 0 与行尾。
     */
    fun computePriorityBreaks(line: CharSequence): PriorityBreaks {
        val len = line.length
        val parenGroups = sortedSetOf<Int>()
        val andOr = sortedSetOf<Int>()
        val operators = sortedSetOf<Int>()
        val commas = sortedSetOf<Int>()
        if (len <= 1) {
            return PriorityBreaks(IntArray(0), IntArray(0), IntArray(0), IntArray(0))
        }

        // 括号匹配，供 R3 使用
        val match = HashMap<Int, Int>()
        val stack = ArrayDeque<Int>()
        for (idx in 0 until len) {
            when (line[idx]) {
                '(' -> stack.addLast(idx)
                ')' -> if (stack.isNotEmpty()) match[stack.removeLast()] = idx
            }
        }

        // R2：and/or 之前（任意深度）
        val matcher = AND_OR.matcher(line)
        while (matcher.find()) {
            andOr.add(matcher.start())
        }

        // R3：含 and/or 的括号组之前（优先级高于 and/or 本身）
        for ((open, close) in match) {
            if (close > open + 1 && AND_OR.matcher(line.subSequence(open + 1, close)).find()) {
                parenGroups.add(open)
            }
        }

        // R4：+ / - 之前，仅当其后的操作数首字符是字母（含汉字）或下划线（任意深度）。
        // 注意：不再对「后跟 (」断行，避免 `(1+(A+B)` 被拆成 `*(1` / `+(…)`。
        for (idx in 0 until len) {
            val c = line[idx]
            if (c != '+' && c != '-') continue
            if (idx == 0 || idx == len - 1) continue
            val prev = line[idx - 1]
            val next = line[idx + 1]
            if (prev.isWhitespace() || next.isWhitespace() || next == ')' || next == '\n') continue
            if (next.isLetter() || next == '_') operators.add(idx)
        }

        // R5：, 之前（任意深度）
        for (idx in 1 until len) {
            if (line[idx] == ',') commas.add(idx)
        }

        // R6：* 与 / 之前（两侧非空白，任意深度）——乘法/除法/路径按符号行首
        for (idx in 1 until len - 1) {
            val c = line[idx]
            if ((c == '*' || c == '/') && !line[idx - 1].isWhitespace() && !line[idx + 1].isWhitespace()) {
                operators.add(idx)
            }
        }

        fun clean(s: Set<Int>): IntArray = s.filter { it > 0 && it < len }.toIntArray()
        return PriorityBreaks(clean(parenGroups), clean(andOr), clean(operators), clean(commas))
    }

    /**
     * 全部首选断点（R1 `\n` + 各优先级分组），升序去重、剔除 0 与行尾。
     * 供单测与兼容使用；布局层请用 [computePriorityBreaks] 分组。
     */
    fun computePreferredBreaks(line: CharSequence): IntArray {
        val len = line.length
        val all = sortedSetOf<Int>()
        var i = 0
        while (i < len - 1) {
            if (line[i] == '\\' && line[i + 1] == 'n') {
                all.add(i)
                i += 2
            } else {
                i++
            }
        }
        val pb = computePriorityBreaks(line)
        all.addAll(pb.parenGroups.toList())
        all.addAll(pb.andOr.toList())
        all.addAll(pb.operators.toList())
        all.addAll(pb.commas.toList())
        return all.filter { it > 0 && it < len }.toIntArray()
    }

    /**
     * 主兜底断点（超宽分段用）：返回 `(start, end)` 内符号集合中的断点位置（新行起始列）。
     * 不含 `.`（`.` 由 [computeDotBreaks] 单独兜底，避免 `.resource` 前导）。
     *
     * 排除规则：
     * - `=` 相邻位置（`范围内=200` 不应断成 `范围内` / `=200`）；
     * - 两个汉字之间（汉字不拆分）。
     */
    fun computeFallbackBreaks(line: CharSequence, start: Int, end: Int): IntArray {
        val out = ArrayList<Int>()
        for (p in (start + 1) until end) {
            val c = line[p]
            if (c == '=') continue
            if (line[p - 1] == '=') continue
            if (c !in FALLBACK_SYMBOLS) continue
            if (p > 0 && isHan(line[p - 1]) && isHan(line[p])) continue
            out.add(p)
        }
        return out.toIntArray()
    }

    /**
     * `.` 兜底断点：仅当其它兜底符号都放不下时才使用（避免 `.resource` 这类前导点）。
     */
    fun computeDotBreaks(line: CharSequence, start: Int, end: Int): IntArray {
        val out = ArrayList<Int>()
        for (p in (start + 1) until end) {
            val c = line[p]
            if (c != '.') continue
            if (line[p - 1] == '=') continue
            if (p > 0 && isHan(line[p - 1]) && isHan(line[p])) continue
            out.add(p)
        }
        return out.toIntArray()
    }

    /** 是否为汉字（CJK 表意文字，含扩展 A 与兼容区）。 */
    fun isHan(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF
    }
}