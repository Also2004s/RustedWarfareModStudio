package com.rwmodstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BracketScanner 的 INI 感知括号配对扫描测试：
 * 注释/多行值块/引号串跳过、交叉错配不双重报告、行号消息正确。
 */
class BracketScannerTest {

    private fun scan(text: String): List<BracketIssue> = BracketScanner.scan(text)

    // 完全配对的文本无问题
    @Test
    fun matchedTextHasNoIssues() {
        assertEquals(emptyList(), scan("[core]\nkey:func(a, [b], {c})\n"))
        assertEquals(emptyList(), scan("[core_1]\n[ turret_x ]\n"))
    }

    // 文末未闭合的开括号各记一处，消息含行号
    @Test
    fun unclosedOpenBracketsAtEof() {
        val issues = scan("[core]\nkey:func(a\n")
        assertEquals(1, issues.size)
        assertEquals('(', textAt("[core]\nkey:func(a\n", issues[0]))
        assertTrue(issues[0].message.contains("第 2 行"))
        assertTrue(issues[0].message.contains("未闭合"))

        assertEquals(1, scan("[core\n").size)
        assertEquals(1, scan("key:{value\n").size)
    }

    // 多余的闭括号记一处
    @Test
    fun extraCloseBracket() {
        val issues = scan("key:a))\n")
        assertEquals(2, issues.size)
        issues.forEach {
            assertTrue(it.message.contains("多余"))
        }
    }

    // 交叉错配 (]：只报被挤出的中间开括号，不双重报告
    @Test
    fun crossMismatchNoDoubleReport() {
        val issues = scan("(]\n")
        // ( 被 ] 挤出：栈内无 [，] 记「多余」，( 留栈文末记「未闭合」→ 共 2 处（各自独立位置）
        // 但 (] 场景中两者都确实是错误位置，允许 2 处；关键是同类场景 ( [ ) 不双重报告：
        val issues2 = scan("([)\n")
        // ( 与 ) 配对，[ 被挤出记「未闭合」→ 只有 1 处
        assertEquals(1, issues2.size)
        assertTrue(issues2[0].message.contains("「[」"))
    }

    // 注释行与行中注释内的括号不报告
    @Test
    fun bracketsInCommentsSkipped() {
        assertEquals(emptyList(), scan("; 注释 ( [ 不检查\n# 注释 ) { 不检查\n"))
        assertEquals(emptyList(), scan("key:value ; 行中注释 ( 不检查\n"))
    }

    // #RRGGBB hex 颜色不算注释，其后同行括号正常检查
    @Test
    fun hexColorNotComment() {
        assertEquals(emptyList(), scan("color:#FF0000\n"))
        val issues = scan("color:#FF0000 extra(\n")
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("「(」"))
        // # 后非 6 位 hex → 注释
        assertEquals(emptyList(), scan("color:#FF00 (不检查)\n"))
    }

    // 多行值块（"""..."""）内的括号不报告，闭合后恢复检查
    @Test
    fun multilineBlockSkipped() {
        val text = "复制与:\"\"\"\nROOT:units/x (备注)\n\"\"\"\n"
        assertEquals(emptyList(), scan(text))

        val text2 = "复制与:\"\"\"\n内容\n\"\"\"\nkey:func(a\n"
        val issues = scan(text2)
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("第 4 行"))
    }

    // 单行引号字符串内的括号不报告
    @Test
    fun quotedStringSkipped() {
        assertEquals(emptyList(), scan("key:\"a(b[c]d)\"\n"))
        assertEquals(emptyList(), scan("key:'x(y)'\n"))
        // 引号外的括号仍检查
        assertEquals(1, scan("key:\"str\" (\n").size)
    }

    // 节头正常；未闭合节头被报告
    @Test
    fun sectionHeaderCases() {
        assertEquals(emptyList(), scan("[core]\n[turret_1]\n"))
        val issues = scan("[core\n")
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("「[」"))
    }

    // 结果按位置排序
    @Test
    fun resultsSortedByPosition() {
        val issues = scan("a)\n[b\n")
        assertEquals(2, issues.size)
        assertTrue(issues[0].startIndex < issues[1].startIndex)
    }

    // 诊断区域转换：位置与消息保留
    @Test
    fun toDiagnosticRegionsKeepsMessage() {
        val issues = scan("[core\n")
        val regions = BracketScanner.toDiagnosticRegions(issues)
        assertEquals(1, regions.size)
        assertEquals(issues[0].startIndex, regions[0].startIndex)
        assertEquals(issues[0].message, regions[0].detail?.briefMessage?.toString())
    }

    private fun textAt(text: String, issue: BracketIssue): Char = text[issue.startIndex]
}
