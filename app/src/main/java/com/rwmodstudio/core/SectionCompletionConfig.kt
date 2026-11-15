package com.rwmodstudio.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一条节补全配置：中文显示名 / 插入用的英文节名 */
@Serializable
data class SectionCompletionEntry(
    val zh: String,
    val en: String
)

/**
 * 节补全配置（开发者模式 → 节补全管理）。
 *
 * 默认使用 [DEFAULT]（19 项，顺序即补全列表顺序）；用户自定义后整体替换，
 * 设置存于 `RWmod/config/settings.json` 的 `section_completion_entries`（JSON 数组）。
 * 空串表示"未自定义"，恢复默认即清空该键。
 */
object SectionCompletionConfig {

    /** 内置默认表（与历史硬编码列表逐项一致，ai 用小写节名） */
    val DEFAULT: List<SectionCompletionEntry> = listOf(
        SectionCompletionEntry("隐藏行动_", "hiddenAction_"),
        SectionCompletionEntry("行动_", "action_"),
        SectionCompletionEntry("全局资源_", "global_resource_"),
        SectionCompletionEntry("资源_", "resource_"),
        SectionCompletionEntry("核心", "core"),
        SectionCompletionEntry("图像", "graphics"),
        SectionCompletionEntry("ai", "ai"),
        SectionCompletionEntry("攻击", "attack"),
        SectionCompletionEntry("炮塔_", "turret_"),
        SectionCompletionEntry("抛射体_", "projectile_"),
        SectionCompletionEntry("运动", "movement"),
        SectionCompletionEntry("效果_", "effect_"),
        SectionCompletionEntry("动画_", "animation_"),
        SectionCompletionEntry("附属_", "attachment_"),
        SectionCompletionEntry("贴花_", "decal"),
        SectionCompletionEntry("leg_", "leg_"),
        SectionCompletionEntry("arm_", "arm_"),
        SectionCompletionEntry("可建造_", "canBuild_"),
        SectionCompletionEntry("放置规则_", "placementRule_")
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: List<SectionCompletionEntry>? = null

    /** 当前生效的节补全表（用户自定义优先，否则内置默认） */
    fun entries(): List<SectionCompletionEntry> {
        cache?.let { return it }
        val raw = SettingsManager.sectionCompletionEntriesJson
        val parsed = if (raw.isBlank()) {
            DEFAULT
        } else {
            runCatching { json.decodeFromString<List<SectionCompletionEntry>>(raw) }
                .getOrDefault(DEFAULT)
        }
        val result = parsed.ifEmpty { DEFAULT }
        cache = result
        return result
    }

    fun isCustomized(): Boolean = SettingsManager.sectionCompletionEntriesJson.isNotBlank()

    /** 保存自定义表并立即生效（补全每次取 [entries]） */
    fun save(entries: List<SectionCompletionEntry>) {
        SettingsManager.sectionCompletionEntriesJson = json.encodeToString(entries)
        cache = entries
    }

    /** 恢复内置默认表 */
    fun reset() {
        SettingsManager.sectionCompletionEntriesJson = ""
        cache = DEFAULT
    }
}
