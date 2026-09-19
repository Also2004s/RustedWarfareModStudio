package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 语义核对的「单位名列表」属性判定测试。
 */
class UnitNameListPropertyTest {

    @Test
    fun matchesUnitNameListProperties() {
        assertTrue(CompletionShared.isUnitNameListProperty("unitsSpawnedOnDeath"), "死亡产生单位")
        assertTrue(CompletionShared.isUnitNameListProperty("overrideAndReplace"), "覆盖单位")
        assertTrue(CompletionShared.isUnitNameListProperty("upgradedFrom"), "升级自")
        // displayLocaleKey 经真实项目实测（corpus_all.txt 近 50 处）值恒为单个单位名，非逗号多值列表（2026-09-19 修正）
        assertFalse(CompletionShared.isUnitNameListProperty("displayLocaleKey"), "界面显示内部调用：单值，见 unitNameProperties")
    }

    @Test
    fun displayLocaleKeyIsSingleValueUnitName() {
        assertTrue(CompletionShared.isUnitNameProperty("displayLocaleKey"), "界面显示内部调用：补单位名但单值")
    }

    @Test
    fun matchesBuiltFromNamePattern() {
        assertTrue(CompletionShared.isUnitNameListProperty("builtFrom_1_name"))
        assertTrue(CompletionShared.isUnitNameListProperty("builtFrom_12_name"))
        assertTrue(CompletionShared.isUnitNameListProperty("BuiltFrom_3_Name"), "should be case-insensitive")
    }

    @Test
    fun rejectsLookalikeButNotUnitNameProperties() {
        assertFalse(CompletionShared.isUnitNameListProperty("altNames"), "别名：自身别名，不是引用其他单位")
        assertFalse(CompletionShared.isUnitNameListProperty("onNewMapSpawn"), "固定特殊单位选项")
        assertFalse(CompletionShared.isUnitNameListProperty("displayName"))
        assertFalse(CompletionShared.isUnitNameListProperty("transportUnitsRequireTag"), "标签类")
        assertFalse(CompletionShared.isUnitNameListProperty("canOnlyBeAttackedByUnitsWithTags"), "标签类")
        assertFalse(CompletionShared.isUnitNameListProperty("nearestUnit"), "logicboolean 操作符")
        assertFalse(CompletionShared.isUnitNameListProperty("parent"))
    }

    @Test
    fun rejectsBuiltFromNonNameSuffixes() {
        assertFalse(CompletionShared.isUnitNameListProperty("builtFrom_1_pos"))
        assertFalse(CompletionShared.isUnitNameListProperty("builtFrom_1_isLocked"))
        assertFalse(CompletionShared.isUnitNameListProperty("builtFrom_1_tooltip"))
        assertFalse(CompletionShared.isUnitNameListProperty("builtFrom_1_name_extra"), "仅完整匹配 builtFrom_N_name")
    }
}
