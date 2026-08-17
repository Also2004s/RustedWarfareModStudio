package com.rwmodstudio.feature.completion.value

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 语义核对的「单位名列表」属性判定测试。
 */
class UnitNameListPropertyTest {

    @Test
    fun matchesUnitNameListProperties() {
        assertTrue(isUnitNameListProperty("unitsSpawnedOnDeath"), "死亡产生单位")
        assertTrue(isUnitNameListProperty("overrideAndReplace"), "覆盖单位")
        assertTrue(isUnitNameListProperty("upgradedFrom"), "升级自")
    }

    @Test
    fun matchesBuiltFromNamePattern() {
        assertTrue(isUnitNameListProperty("builtFrom_1_name"))
        assertTrue(isUnitNameListProperty("builtFrom_12_name"))
        assertTrue(isUnitNameListProperty("BuiltFrom_3_Name"), "should be case-insensitive")
    }

    @Test
    fun rejectsLookalikeButNotUnitNameProperties() {
        assertFalse(isUnitNameListProperty("altNames"), "别名：自身别名，不是引用其他单位")
        assertFalse(isUnitNameListProperty("onNewMapSpawn"), "固定特殊单位选项")
        assertFalse(isUnitNameListProperty("displayName"))
        assertFalse(isUnitNameListProperty("transportUnitsRequireTag"), "标签类")
        assertFalse(isUnitNameListProperty("canOnlyBeAttackedByUnitsWithTags"), "标签类")
        assertFalse(isUnitNameListProperty("nearestUnit"), "logicboolean 操作符")
        assertFalse(isUnitNameListProperty("parent"))
    }

    @Test
    fun rejectsBuiltFromNonNameSuffixes() {
        assertFalse(isUnitNameListProperty("builtFrom_1_pos"))
        assertFalse(isUnitNameListProperty("builtFrom_1_isLocked"))
        assertFalse(isUnitNameListProperty("builtFrom_1_tooltip"))
        assertFalse(isUnitNameListProperty("builtFrom_1_name_extra"), "仅完整匹配 builtFrom_N_name")
    }
}
