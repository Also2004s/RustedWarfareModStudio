package com.rwmodstudio.feature.completion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 补全屏蔽名单测试：锁定三类（弃用 / 非键 / 非法键名）确实被挡，
 * 同时确保普通属性键、保留的模板键、values 区合法函数不被误伤。
 */
class CompletionBlocklistTest {

    @Test
    fun blocksDeprecatedEntries() {
        assertTrue(CompletionBlocklist.isBlocked("action_#_convertTo"))
        assertTrue(CompletionBlocklist.isBlocked("action_#_whenBuilding_cannotMove"))
        assertTrue(CompletionBlocklist.isBlocked("canBuild_#_name/pos/isLocked"))
        assertTrue(CompletionBlocklist.isBlocked("canBuild_#_pos"))
        assertTrue(CompletionBlocklist.isBlocked("动画_类型_开始", "animation_TYPE_start"))
        assertTrue(CompletionBlocklist.isBlocked("animation_TYPE_pingPong"))
    }

    @Test
    fun blocksNonKeyEntriesByBothLanguages() {
        assertTrue(CompletionBlocklist.isBlocked("自身在地上"))
        assertTrue(CompletionBlocklist.isBlocked("self.gound()")) // 表内 nameEn（带括号）
        assertTrue(CompletionBlocklist.isBlocked("self.gound"))   // 翻译库 key（去括号）
        assertTrue(CompletionBlocklist.isBlocked("debugPassthrough(LogicBoolean)"))
        assertTrue(CompletionBlocklist.isBlocked("行动索引/索引", "thisActionIndex/index"))
        assertTrue(CompletionBlocklist.isBlocked("敌对队伍", "aggressiveTeam"))
    }

    @Test
    fun blocksIllegalKeyNames() {
        assertTrue(CompletionBlocklist.isBlocked("\${X}"))
        assertTrue(CompletionBlocklist.isBlocked("\${section.key}"))
        assertTrue(CompletionBlocklist.isBlocked("%{X}"))
        assertTrue(CompletionBlocklist.isBlocked("defineUnitMemory: unit[] squad, float[] mango"))
        assertTrue(
            CompletionBlocklist.isBlocked(
                "defineUnitMemory: boolean nukeActive, boolean laserReady, float experience, " +
                    "unit nextTarget, unit homeBase, string customText"
            )
        )
    }

    @Test
    fun keepsLegitimateEntries() {
        // 普通属性键
        assertFalse(CompletionBlocklist.isBlocked("mass"))
        assertFalse(CompletionBlocklist.isBlocked("质量"))
        // 保留的模板键（未弃用）
        assertFalse(CompletionBlocklist.isBlocked("builtFrom_{NUM}_name"))
        assertFalse(CompletionBlocklist.isBlocked("weaponSlot_{NUM}_name"))
        assertFalse(CompletionBlocklist.isBlocked("text_{LANG}"))
        assertFalse(CompletionBlocklist.isBlocked("arm#_[time]"))
        // 弃用后新的正确写法（保留）
        assertFalse(CompletionBlocklist.isBlocked("start"))
        assertFalse(CompletionBlocklist.isBlocked("缩放开始"))
        assertFalse(CompletionBlocklist.isBlocked("炮塔大小"))
        // values 区合法函数
        assertFalse(CompletionBlocklist.isBlocked("distance(x1, y1, x2, y2)"))
        assertFalse(CompletionBlocklist.isBlocked("select(bool, textA, textB)"))
        assertFalse(CompletionBlocklist.isBlocked("self.hp()"))
    }
}
