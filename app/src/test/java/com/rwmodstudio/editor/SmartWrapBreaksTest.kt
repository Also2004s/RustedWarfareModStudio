package com.rwmodstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartWrapBreaksTest {

    private fun breaks(line: String): List<Int> =
        SmartWrapBreaks.computePreferredBreaks(line).toList()

    // R1：字面 \n 之前断（\ 的索引）
    @Test
    fun literalBackslashNBreaksBeforeBackslash() {
        assertEquals(listOf(3, 8), breaks("第一行\\n第二行\\n第三行"))
        assertEquals(listOf(1, 4), breaks("a\\nb\\nc"))
    }

    // R2：and/or 之前断，任意嵌套深度
    @Test
    fun andOrBreakBeforeOperatorAtAnyDepth() {
        assertEquals(listOf(5, 11), breaks("if A and B or C"))
        // 短括号组内部也参与断（整组放不下时使用），( 在 0 被剔除
        assertEquals(listOf(3), breaks("(A or B)"))
    }

    // R3：含 and/or 的括号组之前断
    @Test
    fun parenGroupContainingAndOrBreaksBeforeOpenParen() {
        assertEquals(listOf(5, 9, 12), breaks("if X and (A or B)"))
    }

    // R4：+/- 仅在后续为命名操作数（字母含汉字/下划线）时断；不再对「后跟 (」断行
    @Test
    fun plusMinusOnlyBreakBeforeNamedOperand() {
        assertEquals(listOf(1), breaks("A+numberOfUnitsInTeam(x)+1-0.25"))
        // R5 逗号也是断点，且断在「逗号之后」：rnd(-2, 2) 断在「, 」之后；-2 本身不断
        assertEquals(listOf(1, 10), breaks("x+rnd(-2, 2)"))
        // 中文标识符操作数（+盟友有…）也能断
        assertEquals(listOf(1), breaks("A+盟友有此单位数量(x)+1"))
        // 后跟 ( 不再断：只保留 B+C 前的 +（问题①：避免 (1+(A+B) 被拆成 *(1 / +(…)
        assertEquals(listOf(4), breaks("A+(B+C)"))
        assertEquals(emptyList(), breaks("a + b"))
    }

    // R4 实测片段：集结度 `(1+(B+C)*0.02)` 不在 `1+` 前断，只在 * 与 +C 前断
    @Test
    fun parenthesesOnePlusGroupNotSplitAtPlus() {
        val b = breaks("集结度: %{A*(1+(B+C)*0.02)}%")
        assertEquals(listOf(8, 14, 17), b)
        assertTrue(11 !in b, "不应在 1+( 的 + 前断行")
    }

    // R5：, 之后断（逗号留在上一行末尾；新行从逗号后第一个非空白字符开始，任意深度）
    @Test
    fun commaBreaksAfterSymbol() {
        assertEquals(listOf(3, 6), breaks("A, B, C"))
        assertEquals(listOf(5), breaks("f(x, y)"))
    }

    // R6：* 之前断（两侧非空白）
    @Test
    fun starBreaksBeforeSymbolWhenTight() {
        assertEquals(listOf(1), breaks("A*B"))
        assertEquals(listOf(2, 5, 7), breaks("(A+B)*C*D"))
        assertEquals(emptyList(), breaks("A * B"))
    }

    // 无规则普通行：不断
    @Test
    fun noPreferredBreaksForPlainLines() {
        assertEquals(emptyList(), breaks("text:hello world"))
        assertEquals(emptyList(), breaks("memory"))
        assertEquals(emptyList(), breaks("hidden:true"))
    }

    // 边界：剔除 0 与行尾，去重
    @Test
    fun excludesStartAndEnd() {
        assertEquals(listOf(3), breaks("\\n and"))
        assertEquals(listOf(2), breaks("A and "))
    }

    // 基于用户样本的断言（区块消失行）
    @Test
    fun sampleConditionalContainsExpectedBreaks() {
        val line = "requireConditional:if numberOfUnitsInEnemyTeam(withTag=\"作战区块\", greaterThan=0) and (numberOfUnitsInTeam(withTag=\"陆军作战中控\", withinRange=250, greaterThan=1) or self.hasResources(冷却=30) and self.hasResources(时间=240))"
        val b = breaks(line).toSet()
        assertTrue(line.indexOf(" and ") + 1 in b, "外层 and 前断")
        assertTrue(line.indexOf(" or ") + 1 in b, "括号内 or 前断")
        assertTrue(line.indexOf("(numberOfUnitsInTeam") in b, "含 or 的括号组前断")
    }

    // 算术链：命名操作数断、裸数字不断
    @Test
    fun arithmeticChainNamedOperandOnly() {
        val plus = "集结度: %{A+numberOfUnitsInTeam(x)}%"
        val pb = breaks(plus).toSet()
        assertTrue(plus.indexOf('+') in pb, "命名 + 前断")

        val bare = "战力比:%{(self.resource.总战力+1)/(memory.攻击目标.resource.总战力+1)}"
        assertTrue(breaks(bare).all { it != bare.indexOf('+') }, "裸数字 +1 不断")
    }

    // computeFallbackBreaks：排除 = 相邻、排除两个汉字之间；保留 ( 等标点
    @Test
    fun fallbackBreaksExcludeEqualsAndHanInterior() {
        assertEquals(emptyList(), SmartWrapBreaks.computeFallbackBreaks("范围内=200", 0, 7).toList())
        assertEquals(emptyList(), SmartWrapBreaks.computeFallbackBreaks("x=y", 0, 3).toList())
        val han = "队伍中此单位数量(x)"
        val fb = SmartWrapBreaks.computeFallbackBreaks(han, 0, han.length).toList()
        assertEquals(listOf(han.indexOf('(')), fb, "在 ( 前断，且不在汉字内部断")
    }
    // 优先级分组：括号组 > and/or > 括号外逗号 > 运算符号 > 括号内逗号
    @Test
    fun priorityBreaksGroupedByPriority() {
        val line = "if (A or B) and C+D*E, F"
        val pb = SmartWrapBreaks.computePriorityBreaks(line)
        assertEquals(listOf(3), pb.parenGroups.toList(), "含 or 的括号组")
        assertEquals(listOf(6, 12), pb.andOr.toList(), "or/and")
        assertEquals(listOf(23), pb.outerCommas.toList(), "括号外逗号在逗号之后，优先于运算符")
        assertEquals(listOf(17, 19), pb.operators.toList(), "+ / *（紧凑运算符号）")
        assertTrue(pb.innerCommas.isEmpty(), "无括号内逗号")
    }
    // 括号外逗号（资源名列表分隔）优先于运算符；括号内逗号归最低档，不与运算符抢位置
    @Test
    fun outerCommaOutranksOperatorWhileInnerCommaDoesNot() {
        val pb = SmartWrapBreaks.computePriorityBreaks("A=(x), B=(y+z)")
        assertEquals(listOf(7), pb.outerCommas.toList(), "括号外逗号之后（跳过逗号后的空格）")
        assertEquals(listOf(11), pb.operators.toList(), "括号内 + 属运算符档")
        assertTrue(pb.innerCommas.isEmpty())

        val pb2 = SmartWrapBreaks.computePriorityBreaks("max(a/b, 1)")
        assertTrue(pb2.outerCommas.isEmpty(), "括号内逗号不进外层档")
        assertEquals(listOf(5), pb2.operators.toList(), "/ 仍是运算符档")
        assertEquals(listOf(9), pb2.innerCommas.toList(), "括号内逗号之后")
    }
    // / 加入运算符号：A/(B+C) 在 / 与 + 前断；路径 ROOT:/机制 在 / 前断
    @Test
    fun slashAsOperatorBreak() {
        val pb = SmartWrapBreaks.computePriorityBreaks("A/(B+C)")
        assertEquals(listOf(1, 4), pb.operators.toList(), "/ 与 + 均为运算符号")
        val path = SmartWrapBreaks.computePriorityBreaks("ROOT:/机制")
        assertEquals(listOf(5), path.operators.toList(), "路径 / 前断")
    }

    // 兜底不再含 .，. 由 computeDotBreaks 单独兜底
    @Test
    fun fallbackExcludesDotButDotBreaksWork() {
        assertEquals(emptyList(), SmartWrapBreaks.computeFallbackBreaks("a.b", 0, 3).toList())
        assertEquals(listOf(1), SmartWrapBreaks.computeDotBreaks("a.b", 0, 3).toList())
    }

    // 兜底断点里的逗号同样走「逗号之后」，且结果保持升序（布局层依赖升序 + 早停）
    @Test
    fun fallbackCommaBreaksAfterSymbol() {
        assertEquals(listOf(2), SmartWrapBreaks.computeFallbackBreaks("A,B", 0, 3).toList())
        assertEquals(listOf(2, 3), SmartWrapBreaks.computeFallbackBreaks("A, B", 0, 4).toList())
    }
}
