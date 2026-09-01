package com.rwmodstudio.core

internal fun String.normalizedForDiff(): String = this.filter { !it.isWhitespace() }

// ' '=未变, '-'=删除, '+'=新增；oldLine/newLine 为 1-based 行号，无对应侧为 -1
data class DiffOp(val type: Char, val text: String, val oldLine: Int, val newLine: Int)

// 基于 LCS 的逐行 diff，插入/删除一行不会让后续未修改的行误判为变更
// 比较时忽略空格，显示仍保留原样
fun computeLineDiff(before: List<String>, after: List<String>): List<DiffOp> {
    val m = before.size
    val n = after.size
    // 每行只归一化一次，避免在 O(m*n) 的 DP 循环里反复 filter 生成新字符串（大文件时会拖慢数百毫秒）
    val nb = before.map { it.normalizedForDiff() }
    val na = after.map { it.normalizedForDiff() }
    // 保持 400 万格阈值：铁锈 MOD 的 ini 常见 1000-2000 行，降到 100 万格会让 1000 行以上的文件
    // 走「按位置配对」退化算法（中间插入一行会显示成整段删+增，diff 精度明显下降）。
    // 4M 格 IntArray ≈ 16MB，手动触发的版本对比场景可接受；恢复原阈值保证差异显示质量。
    if (m.toLong() * n > 4_000_000L) {
        // 文件过大时退化为按位置配对，避免 O(m*n) 内存占用
        val ops = ArrayList<DiffOp>(maxOf(m, n))
        for (i in 0 until maxOf(m, n)) {
            val bn = nb.getOrNull(i); val an = na.getOrNull(i)
            when {
                bn == null -> ops.add(DiffOp('+', after.getOrNull(i) ?: "", -1, i + 1))
                an == null -> ops.add(DiffOp('-', before[i], i + 1, -1))
                bn == an -> ops.add(DiffOp(' ', before[i], i + 1, i + 1))
                else -> { ops.add(DiffOp('-', before[i], i + 1, -1)); ops.add(DiffOp('+', after.getOrNull(i) ?: "", -1, i + 1)) }
            }
        }
        return ops
    }
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            dp[i][j] = if (nb[i] == na[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val ops = ArrayList<DiffOp>(m + n)
    var i = 0; var j = 0
    while (i < m && j < n) {
        when {
            nb[i] == na[j] -> { ops.add(DiffOp(' ', before[i], i + 1, j + 1)); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { ops.add(DiffOp('-', before[i], i + 1, -1)); i++ }
            else -> { ops.add(DiffOp('+', after[j], -1, j + 1)); j++ }
        }
    }
    while (i < m) { ops.add(DiffOp('-', before[i], i + 1, -1)); i++ }
    while (j < n) { ops.add(DiffOp('+', after[j], -1, j + 1)); j++ }
    return ops
}
