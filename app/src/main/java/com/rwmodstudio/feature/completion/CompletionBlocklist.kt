package com.rwmodstudio.feature.completion

/**
 * 补全条目屏蔽名单（单一来源）。
 *
 * 这些条目来自附件补全表（`extra_completions.json`）与代码参考库（`code_reference.json`），
 * 但都不该出现在自动补全的候选列表里，原因分三类：
 *  - **A 已弃用**：说明里明确写了「已弃用 / 请改用…」，已有新写法替代；
 *  - **B 非键**：逻辑函数 / 逻辑变量 / 函数参数，应归「值分类」而不是某个节的属性键；
 *  - **C 非法键名**：含空格 / 冒号 / `$` / `%` 等，选中会插入废键。
 *
 * 作用范围仅限**补全候选**（含翻译库兜底），不动数据文件，
 * 因此「自定义补全」管理页与「补全类型」分类页照旧按数据展示。
 * 判定按 name / nameEn / label 三个字段任意命中即屏蔽。
 */
internal object CompletionBlocklist {

    /** 结尾参数括号：`self.gound()` → `self.gound`、`debugPassthrough(LogicBoolean)` → `debugPassthrough`。 */
    private val TRAILING_PARENS = Regex("""\(.*\)$""")

    /** 规范化：去首尾空白 + 去掉结尾的 `(...)`，使表内写法与翻译库 key（已去括号）能一致比对。 */
    private fun normalize(name: String): String = name.trim().replace(TRAILING_PARENS, "").trim()

    /** 屏蔽名单（统一以「去结尾括号」形式登记）。 */
    private val BLOCKED: Set<String> = setOf(
        // ===== A. 已弃用（说明里写明「已弃用 / 请改用」）=====
        // 核心 action_#_* → 改用独立 [action_x] 节
        "action_#_convertTo",
        "action_#_pos",
        "action_#_price",
        "action_#_text",
        "action_#_description",
        "action_#_addEnergy",
        "action_#_whenBuilding_cannotMove",
        // 核心 canBuild_#_* → 改用 canBuild 部分
        "canBuild_#_name/pos/isLocked",
        "canBuild_#_name",
        "canBuild_#_pos",
        "canBuild_#_isLocked",
        // 图像 动画_类型_*（animation_TYPE_*）→ 改用 [animation] 节（动画节的 开始/结束/缩放开始/缩放结束/速度/缩放效果）
        "动画_类型_开始", "animation_TYPE_start",
        "动画_类型_结束", "animation_TYPE_end",
        "动画_类型_缩放开始", "animation_TYPE_scale_start",
        "动画_类型_缩放结束", "animation_TYPE_scale_end",
        "动画_类型_速度", "animation_TYPE_speed",
        "动画_类型_膨胀", "animation_TYPE_pingPong",

        // ===== B. 非键（逻辑函数 / 变量 / 参数，应归值分类）=====
        "自身在地上", "self.gound",
        "自身在卸载单位", "self.isTransportUnloading",
        "事件数据", "eventData",
        "自身最大速度", "self.maxspeed",
        "自身连接数", "self.numberOfConnections",
        "调试通行", "debugPassthrough",
        "行动索引/索引", "thisActionIndex/index",
        "敌对队伍", "aggressiveTeam",

        // ===== C. 名字不是合法键（选中会插入废键）=====
        "defineUnitMemory: boolean nukeActive, boolean laserReady, float experience, unit nextTarget, unit homeBase, string customText",
        "defineUnitMemory: unit[] squad, float[] mango",
        "\${X}",
        "\${section.key}",
        "%{X}"
    )

    /** 任一名（原始 name / nameEn / label，或翻译库 key）命中屏蔽名单即返回 true。 */
    fun isBlocked(vararg candidates: String): Boolean =
        candidates.any { it.isNotBlank() && normalize(it) in BLOCKED }
}
