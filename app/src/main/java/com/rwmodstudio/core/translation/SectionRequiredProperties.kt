package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * 节内「必备属性」表（内置静态资源，非生成物，不写 RWmod、不配验证码）。
 *
 * 数据来自 assets/data/section_required.json：`{ "核心": ["name","mass",...], ... }`。
 * 用于「节内空行点击代码提示」时列出该节必备、但当前（含继承链）尚未出现的属性。
 * 无条目的节返回 null，调用方回退为“该节全部合法属性”（现有节补全行为）。
 *
 * 数据来源 = **三处独立来源交叉验证**：
 *
 * ① 游戏本体的必填校验（RW 1.15 反编译源码，仓库 `.tmp_rw_source/`）：
 * - `gameFramework/utility/UnitConfig.java`：`getValue(section,key,false)` / `throwNoKeyInSectionException`
 *   读不到即抛 `Could not find <key> in configuration file in section:<section>`；
 *   必填读取入口 = `e()`（必填字符串 L424）、`g()`（必填 int L671）、`i()`（必填 float L830）、
 *   `getValueAsPrimeBoolean()`（L415）。
 * - `game/units/custom/class_436.java`（单位加载主流程，`var12/13/14/15` = core/graphics/attack/movement）：
 *   L1546 core.name、L1697 graphics.image、L1877 core.mass、L1881 core.price、L2116 core.maxHp、
 *   L2131 core.radius、L2366 movement.movementType、L2864 attack.canAttack —— 全部无条件必填读取。
 *   （core.price 走 `custom/d/class_447.java` L100 的 `a(..., boolean optional)`，传 `false` → L103 抛
 *   `Could not find price in configuration file under:core`，故 price 同属必填。）
 * - `game/units/custom/class_397.java` L178-179：每个 `[turret_*]` 必填 `x`、`y`。
 * - `game/units/custom/class_431.java` L117-118：每个 `[leg_N]`/`[arm_N]` 必填 `x`、`y`。
 *
 * ② 官方 Modding Reference 表格（`Rusted Warfare: Unit Modding Reference`，Corroding Games 官网
 *   "Modding" 一节给出链接）中名为 **`!!! THESE ARE REQUIRED CODE !!!`**、并在 `[core]` 表头标注
 *   `Necessary Code: will cause error if these are not included` 的区块，逐条为：
 *   `[core] name/maxHp/price/mass/radius`、`[graphics] image`、`[movement] movementType`，
 *   ——与本表**完全一致**。
 *
 * ③ 中文社区教程（9k9k 铁锈战争 v1.15 "铁锈战争mod常用代码"）把 `[core]#核心(不可缺)`、
 *   `[graphics]#图像(不可缺)`、`[attack]#攻击(不可缺)` 明确标注为「不可缺」。
 *
 * 另有若干**条件必填**（不在此表，只在满足条件时才报错）：
 * - `graphics.imageShadow`：当 `image` 且 `shadow:true` 时（class_429 L499）；
 * - `attack` 的 `altProjectileCondition`：当用了 `altProjectile` 时（class_397 L353）；
 * - `addWaypoint_type` 及配套：当用 `addWaypoint_*` 时（class_a/a/class_786 L107）；
 * - `meleeEngangementDistance` 需先有 `isMelee:true`（官方文档）。
 *
 * **刻意不收的两项**：官方表格的 REQUIRED 区块另列了 `[attack] canAttackFlyingUnits /
 * canAttackLandUnits / canAttackUnderwaterUnits` 三行，但反编译代码里它们在 `canAttack` 为 true 时
 * 走 `var11.a(unit, section, key)`（默认 null）读取、为 false 时直接取 `LogicBoolean.falseBoolean`
 * ——**有默认值、缺失不报错**，故按「缺失即报错」的口径不纳入本表，避免对 `canAttack:false` 的单位
 * 产生虚假的「缺属性」提示。
 */
object SectionRequiredProperties {

    private const val TAG = "SectionRequiredProperties"
    private const val ASSET_PATH = "data/section_required.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var loaded = false

    /** 节分类名 -> 归一化（trim + 小写）后的必备键集合 */
    @Volatile
    private var required: Map<String, Set<String>> = emptyMap()

    /** 后台预加载（幂等，建议在 IO 线程调用）。 */
    fun load(context: Context) {
        if (loaded) return
        try {
            val text = context.assets.open(ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = json.decodeFromString<Map<String, List<String>>>(text)
            required = parsed.mapValues { (_, keys) ->
                keys.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            }
            loaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_PATH", e)
        }
    }

    /** 某节（已归一化的分类名）的必备属性键集合；无条目返回 null。 */
    fun requiredKeysFor(sectionName: String): Set<String>? = required[sectionName]
}