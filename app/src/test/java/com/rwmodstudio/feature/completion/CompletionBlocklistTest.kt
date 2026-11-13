package com.rwmodstudio.feature.completion

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 补全隐藏名单测试：
 * - 默认仍挡住「已弃用 / 非键 / 非法键名」三类；
 * - `动画_类型_*` 已从默认名单放出；
 * - 用户可以放出默认项、追加/删除自定义项，并可恢复默认。
 *
 * 单测环境拿不到 Android 存储路径，配置只在内存里生效（持久化代码会静默跳过），
 * 因此每个用例前后都恢复默认，避免彼此污染。
 */
class CompletionBlocklistTest {

    @BeforeTest
    fun resetBefore() {
        CompletionBlocklist.resetToDefault()
    }

    @AfterTest
    fun resetAfter() {
        CompletionBlocklist.resetToDefault()
    }

    @Test
    fun blocksDeprecatedEntries() {
        assertTrue(CompletionBlocklist.isBlocked("action_#_convertTo"))
        assertTrue(CompletionBlocklist.isBlocked("action_#_whenBuilding_cannotMove"))
        assertTrue(CompletionBlocklist.isBlocked("canBuild_#_name/pos/isLocked"))
        assertTrue(CompletionBlocklist.isBlocked("canBuild_#_pos"))
    }

    @Test
    fun releasesAnimationTypeEntries() {
        assertFalse(CompletionBlocklist.isBlocked("动画_类型_开始", "animation_TYPE_start"))
        assertFalse(CompletionBlocklist.isBlocked("动画_类型_结束", "animation_TYPE_end"))
        assertFalse(CompletionBlocklist.isBlocked("动画_类型_缩放开始", "animation_TYPE_scale_start"))
        assertFalse(CompletionBlocklist.isBlocked("动画_类型_缩放结束", "animation_TYPE_scale_end"))
        assertFalse(CompletionBlocklist.isBlocked("动画_类型_速度", "animation_TYPE_speed"))
        assertFalse(CompletionBlocklist.isBlocked("动画_类型_膨胀", "animation_TYPE_pingPong"))
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

    @Test
    fun defaultEntryCanBeReleasedAndRestored() {
        val entry = CompletionBlocklist.DEFAULT_ENTRIES.first { it.display == "自身在地上" }
        assertTrue(CompletionBlocklist.isDefaultHidden(entry))

        CompletionBlocklist.setDefaultHidden(entry, false)
        assertFalse(CompletionBlocklist.isDefaultHidden(entry))
        assertFalse(CompletionBlocklist.isBlocked("自身在地上", "self.gound"))
        assertTrue(CompletionBlocklist.hasUserChanges())

        CompletionBlocklist.setDefaultHidden(entry, true)
        assertTrue(CompletionBlocklist.isDefaultHidden(entry))
        assertTrue(CompletionBlocklist.isBlocked("自身在地上", "self.gound"))
    }

    @Test
    fun userCanAddAndRemoveExtraEntry() {
        assertTrue(CompletionBlocklist.addExtra("我的自定义键"))
        assertTrue(CompletionBlocklist.isBlocked("我的自定义键"))
        assertFalse(CompletionBlocklist.addExtra("我的自定义键")) // 重复
        assertFalse(CompletionBlocklist.addExtra("  "))

        CompletionBlocklist.removeExtra("我的自定义键")
        assertFalse(CompletionBlocklist.isBlocked("我的自定义键"))
        assertFalse(CompletionBlocklist.hasUserChanges())
    }

    @Test
    fun resetRestoresDefaults() {
        val entry = CompletionBlocklist.DEFAULT_ENTRIES.first { it.display == "敌对队伍" }
        CompletionBlocklist.addExtra("我的自定义键")
        CompletionBlocklist.setDefaultHidden(entry, false)

        CompletionBlocklist.resetToDefault()

        assertFalse(CompletionBlocklist.isBlocked("我的自定义键"))
        assertTrue(CompletionBlocklist.isDefaultHidden(entry))
        assertFalse(CompletionBlocklist.hasUserChanges())
    }
}
