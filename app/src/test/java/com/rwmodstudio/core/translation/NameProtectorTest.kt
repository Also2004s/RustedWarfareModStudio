package com.rwmodstudio.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 四类名字在整行翻译链路里的原位保护（[NameProtector]）。
 * JVM 单测中 SettingsManager 未初始化，开关按默认值（全开）处理。
 */
class NameProtectorTest {

    private fun protectAndRestore(value: String, keyEn: String): Pair<String, String> {
        val (masked, ph) = NameProtector.protect(value, keyEn)
        return masked to NameProtector.restore(masked, ph)
    }

    @Test
    fun resourceNamesInResourcePropertyValueAreProtected() {
        val (masked, restored) = protectAndRestore("移动=0, 能量=5", "setResourcesWithLogic")
        assertFalse(masked.contains("移动"), "资源名=值 左侧的名字不应参与翻译：$masked")
        assertFalse(masked.contains("能量"), "逗号分隔的第二个资源名也不应参与翻译：$masked")
        assertEquals("移动=0, 能量=5", restored)
    }

    @Test
    fun standaloneResourceNameIsProtectedButNumbersAreNot() {
        val (masked, restored) = protectAndRestore("资金", "displayTextAppendResource")
        assertFalse(masked.contains("资金"), "裸资源名不应翻译：$masked")
        assertEquals("资金", restored)

        val (numberMasked, numberRestored) = protectAndRestore("100", "price")
        assertEquals("100", numberMasked, "纯数字不是资源名，不应打占位")
        assertEquals("100", numberRestored)
    }

    @Test
    fun resourceNamesAfterResourceDotAreProtected() {
        val (masked, restored) = protectAndRestore("自身资源.移动 + 当前动作目标.资源.能量", "requirement")
        assertFalse(masked.contains("移动"), "资源链点后的名字不应翻译：$masked")
        assertFalse(masked.contains("能量"), "多级资源链点后的名字不应翻译：$masked")
        assertEquals("自身资源.移动 + 当前动作目标.资源.能量", restored)
    }

    @Test
    fun hasResourcesArgumentsAreProtected() {
        val (masked, restored) = protectAndRestore("自身有资源(移动=5, 能量=3)", "requirement")
        assertFalse(masked.contains("移动"), "自身有资源 参数名不应翻译：$masked")
        assertEquals("自身有资源(移动=5, 能量=3)", restored)
    }

    @Test
    fun tagParameterValuesAreProtected() {
        val (masked, restored) = protectAndRestore("自身有标签(有标签=空中)", "requirement")
        assertFalse(masked.contains("空中"), "标签参数值不应翻译：$masked")
        assertEquals("自身有标签(有标签=空中)", restored)
    }

    @Test
    fun memoryNamesAreProtected() {
        val (masked, restored) = protectAndRestore("内存.攻击目标 + 读取单位内存('攻击目标')", "requirement")
        assertFalse(masked.contains("攻击目标"), "内存名不应翻译：$masked")
        assertEquals("内存.攻击目标 + 读取单位内存('攻击目标')", restored)
    }

    @Test
    fun whitelistedResourceNameIsNotProtected() {
        val value = "自身资源.credits + 自身资源.移动"
        val (masked, originals) = NameProtector.protect(
            value,
            "requirement",
            allowResourceName = { it == "credits" }
        )
        assertTrue(masked.contains("credits"), "白名单资源名应允许翻译：$masked")
        assertFalse(masked.contains("移动"), "名单外资源名应保持原文：$masked")
        assertEquals(value, NameProtector.restore(masked, originals))
    }

    @Test
    fun resourceNameStopsAtComparisonOperator() {
        val value = "自身资源.总战力<memory.攻击目标.资源.总战力*1.08"
        val (masked, restored) = protectAndRestore(value, "requirement")
        assertFalse(masked.contains("总战力"), "资源名应被保护：$masked")
        assertTrue(masked.contains("memory"), "比较符后的 memory 不应被吞进资源名：$masked")
        assertEquals(value, restored)
    }

    @Test
    fun nestedFunctionParamsAreNotTreatedAsResourceNames() {
        val value = "对地范围=选择(敌人有此单位数量(需标签=\"对地\",withinRange=500,greaterThan=0),0)"
        val (masked, restored) = protectAndRestore(value, "setResourcesWithLogic")
        assertFalse(masked.contains("对地范围"), "最外层资源名应被保护：$masked")
        assertTrue(masked.contains("withinRange"), "嵌套参数名不应被保护：$masked")
        assertTrue(masked.contains("greaterThan"), "嵌套参数名不应被保护：$masked")
        assertEquals(value, restored)
    }

    @Test
    fun doubleQuotedTagValueWithSpaceIsFullyProtected() {
        val value = "自身有标签(有标签=\"海军 进攻\")"
        val (masked, restored) = protectAndRestore(value, "requirement")
        assertFalse(masked.contains("海军"), "双引号内带空格的名字也应完整保护：$masked")
        assertEquals(value, restored)
    }

    @Test
    fun variableReferencesAreProtected() {
        val (masked, restored) = protectAndRestore("\${移动} + 5", "requirement")
        assertFalse(masked.contains("移动"), "变量引用不应翻译：$masked")
        assertEquals("\${移动} + 5", restored)
    }
}
