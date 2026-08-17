package com.rwmodstudio.feature.completion.value

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValueProviderTypeTest {

    @Test
    fun boolTypeMatchesNormalizedBools() {
        assertTrue(isBoolValueType("bool"), "bool should match")
        assertTrue(isBoolValueType("boolean"), "boolean should match")
        assertTrue(isBoolValueType("Boolean"), "Boolean should match")
        assertTrue(isBoolValueType("bool -?"), "bool -? (帧随机) should match")
        assertTrue(isBoolValueType("  BOOL "), "case/whitespace should be normalized")
    }

    @Test
    fun boolTypeRejectsCompositeAndOtherTypes() {
        // 组合型 bool/X 取 bool 分支（友伤 bool/string、valueInStats bool/int、尾焰 bool/effect）
        assertTrue(isBoolValueType("bool/string"), "友伤 bool/string should match")
        assertTrue(isBoolValueType("bool/int"), "valueInStats bool/int should match")
        assertTrue(isBoolValueType("bool/effect"), "尾焰 bool/effect should match")
        assertTrue(isBoolValueType("BOOL / STRING"), "组合型也应归一化")
        assertFalse(isBoolValueType("LogicBoolean"), "LogicBoolean should not match")
        assertFalse(isBoolValueType("string"), "string should not match")
        assertFalse(isBoolValueType("string/bool"), "非 bool 前缀组合不算")
        assertFalse(isBoolValueType(""), "empty should not match")
    }

    @Test
    fun logicBooleanTypeMatchesNormalizedTypes() {
        assertTrue(isLogicBooleanValueType("LogicBoolean"), "LogicBoolean should match")
        assertTrue(isLogicBooleanValueType("logicboolean"), "logicboolean should match")
        assertTrue(isLogicBooleanValueType("logic boolean"), "logic boolean (隐藏) should match")
        assertTrue(isLogicBooleanValueType(" LogicBoolean "), "surrounding whitespace should be ignored")
    }

    @Test
    fun logicBooleanTypeRejectsOthers() {
        assertFalse(isLogicBooleanValueType("bool"), "bool should not match")
        assertFalse(isLogicBooleanValueType("logicNumber"), "logicNumber should not match")
        assertFalse(isLogicBooleanValueType("logic"), "logic should not match")
        assertFalse(isLogicBooleanValueType(""), "empty should not match")
    }
}
