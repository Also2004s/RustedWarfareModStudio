package com.rwmodstudio.core

private fun String.normalizedForDiff(): String = this.filter { !it.isWhitespace() }

// ' '=未变, '-'=删除, '+'=新增；oldLine/newLine 为 1-based 行号，无对应侧为 -1
data class DiffOp(val type: Char, val text: String, val oldLine: Int, val newLine: Int)

// 基于 LCS 的逐行 diff，插入/删除一行不会让后续未修改的行误判为变更
// 比较时忽略空格，显示仍保留原样
fun computeLineDiff(before: List<String>, after: List<String>): List<DiffOp> {
    val m = before.size
    val n = after.size
    if (m.toLong() * n > 4_000_000L) {
        // 文件过大时退化为按位置配对，避免 O(m*n) 内存占用
        val ops = ArrayList<DiffOp>(maxOf(m, n))
        for (i in 0 until maxOf(m, n)) {
            val b = before.getOrNull(i); val a = after.getOrNull(i)
            when {
                b == null -> ops.add(DiffOp('+', a ?: "", -1, i + 1))
                a == null -> ops.add(DiffOp('-', b, i + 1, -1))
                b.normalizedForDiff() == a.normalizedForDiff() -> ops.add(DiffOp(' ', b, i + 1, i + 1))
                else -> { ops.add(DiffOp('-', b, i + 1, -1)); ops.add(DiffOp('+', a, -1, i + 1)) }
            }
        }
        return ops
    }
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            dp[i][j] = if (before[i].normalizedForDiff() == after[j].normalizedForDiff()) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val ops = ArrayList<DiffOp>(m + n)
    var i = 0; var j = 0
    while (i < m && j < n) {
        when {
            before[i].normalizedForDiff() == after[j].normalizedForDiff() -> { ops.add(DiffOp(' ', before[i], i + 1, j + 1)); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { ops.add(DiffOp('-', before[i], i + 1, -1)); i++ }
            else -> { ops.add(DiffOp('+', after[j], -1, j + 1)); j++ }
        }
    }
    while (i < m) { ops.add(DiffOp('-', before[i], i + 1, -1)); i++ }
    while (j < n) { ops.add(DiffOp('+', after[j], -1, j + 1)); j++ }
    return ops
}
