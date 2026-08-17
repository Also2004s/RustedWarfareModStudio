package com.rwmodstudio.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffUtilTest {

    @Test
    fun identicalTextsProduceOnlyUnchanged() {
        val before = listOf("a", "b", "c")
        val after = listOf("a", "b", "c")
        val ops = computeLineDiff(before, after)
        assertEquals(3, ops.size)
        assertTrue(ops.all { it.type == ' ' }, "identical lines should be unchanged")
        assertEquals(listOf(1, 2, 3), ops.map { it.oldLine })
        assertEquals(listOf(1, 2, 3), ops.map { it.newLine })
    }

    @Test
    fun insertedLineProducesPlus() {
        val before = listOf("a", "c")
        val after = listOf("a", "b", "c")
        val ops = computeLineDiff(before, after)
        val plus = ops.filter { it.type == '+' }
        assertEquals(1, plus.size)
        assertEquals("b", plus[0].text)
        assertEquals(2, plus[0].newLine)
        assertEquals(-1, plus[0].oldLine)
    }

    @Test
    fun deletedLineProducesMinus() {
        val before = listOf("a", "b", "c")
        val after = listOf("a", "c")
        val ops = computeLineDiff(before, after)
        val minus = ops.filter { it.type == '-' }
        assertEquals(1, minus.size)
        assertEquals("b", minus[0].text)
        assertEquals(2, minus[0].oldLine)
        assertEquals(-1, minus[0].newLine)
    }

    @Test
    fun modifiedLineProducesMinusAndPlus() {
        val before = listOf("a", "b", "c")
        val after = listOf("a", "B", "c")
        val ops = computeLineDiff(before, after)
        val changed = ops.filter { it.type != ' ' }
        assertTrue(changed.any { it.type == '-' }, "should contain a deletion")
        assertTrue(changed.any { it.type == '+' }, "should contain an insertion")
    }

    @Test
    fun whitespaceDifferencesIgnoredForMatch() {
        val before = listOf("a b")
        val after = listOf("a  b") // extra space: normalized equal
        val ops = computeLineDiff(before, after)
        assertEquals(' ', ops[0].type)
    }

    @Test
    fun hugeInputUsesDegenerateBranchWithoutOom() {
        val n = 5000 // m*n = 25M > 4M -> degenerate branch
        val before = List(n) { "line$it" }
        val after = before.toMutableList().apply { set(n / 2, "line${n / 2} modified") }
        val ops = computeLineDiff(before, after)
        assertTrue(ops.isNotEmpty())
        // 退化分支按位置配对：1 行修改产生 2 个 op（- 和 +），其余 n-1 行各 1 个
        assertEquals(n + 1, ops.size)
    }
}