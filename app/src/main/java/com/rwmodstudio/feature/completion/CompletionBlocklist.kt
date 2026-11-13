package com.rwmodstudio.feature.completion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.util.atomicWriteText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 补全条目屏蔽名单（单一来源，用户可编辑）。
 *
 * 默认名单来自附件补全表（`extra_completions.json`）与代码参考库（`code_reference.json`），
 * 分三类：
 *  - **A 已弃用**：说明里明确写了「已弃用 / 请改用…」，已有新写法替代；
 *  - **B 非键**：逻辑函数 / 逻辑变量 / 函数参数，应归「值分类」而不是某个节的属性键；
 *  - **C 非法键名**：含空格 / 冒号 / `$` / `%` 等，选中会插入废键。
 *
 * 用户在「开发者模式 → 补全隐藏列表」里可以把默认项放出、或追加自己的隐藏项，
 * 配置存 `RWmod/config/completion_blocklist.json`（用户配置，非生成物，无需验证码）。
 *
 * 作用范围仅限**补全候选**（含翻译库兜底），不动数据文件，
 * 因此「自定义补全」管理页与「补全类型」分类页照旧按数据展示。
 * 判定按 name / nameEn / label 三个字段任意命中即屏蔽。
 */
internal object CompletionBlocklist {

    /** 默认屏蔽项的原因分组（界面按此分组展示）。 */
    enum class Reason(val label: String) {
        DEPRECATED("已弃用"),
        NON_KEY("非键（逻辑函数 / 变量 / 参数）"),
        ILLEGAL_NAME("非法键名")
    }

    /**
     * 一条默认屏蔽项。
     * @param display 界面主标题
     * @param names 参与比对的全部写法（中文名 / 英文名 / 表内带括号写法）
     * @param note 补充说明（为什么屏蔽 / 应该改用什么）
     */
    data class Entry(
        val display: String,
        val names: List<String>,
        val reason: Reason,
        val note: String = ""
    )

    /**
     * 默认屏蔽名单。
     * 注意：`动画_类型_*`（animation_TYPE_*）已从默认名单放出，可正常补全。
     */
    val DEFAULT_ENTRIES: List<Entry> = listOf(
        // ===== A. 已弃用（说明里写明「已弃用 / 请改用」）=====
        Entry(
            "action_#_convertTo", listOf("action_#_convertTo"),
            Reason.DEPRECATED, "改用独立 [action_x] 节"
        ),
        Entry(
            "action_#_pos", listOf("action_#_pos"),
            Reason.DEPRECATED, "改用独立 [action_x] 节"
        ),
        Entry(
            "action_#_price", listOf("action_#_price"),
            Reason.DEPRECATED, "改用独立 [action_x] 节"
        ),
        Entry(
            "action_#_text", listOf("action_#_text"),
            Reason.DEPRECATED, "改用独立 [action_x] 节"
        ),
        Entry(
            "action_#_description", listOf("action_#_description"),
            Reason.DEPRECATED, "改用独立 [action_x] 节"
        ),
        Entry(
            "action_#_addEnergy", listOf("action_#_addEnergy"),
            Reason.DEPRECATED, "改用独立 [action_x] 节"
        ),
        Entry(
            "action_#_whenBuilding_cannotMove", listOf("action_#_whenBuilding_cannotMove"),
            Reason.DEPRECATED, "改用独立 [action_x] 节"
        ),
        Entry(
            "canBuild_#_name/pos/isLocked", listOf("canBuild_#_name/pos/isLocked"),
            Reason.DEPRECATED, "改用 canBuild 部分"
        ),
        Entry(
            "canBuild_#_name", listOf("canBuild_#_name"),
            Reason.DEPRECATED, "改用 canBuild 部分"
        ),
        Entry(
            "canBuild_#_pos", listOf("canBuild_#_pos"),
            Reason.DEPRECATED, "改用 canBuild 部分"
        ),
        Entry(
            "canBuild_#_isLocked", listOf("canBuild_#_isLocked"),
            Reason.DEPRECATED, "改用 canBuild 部分"
        ),

        // ===== B. 非键（逻辑函数 / 变量 / 参数，应归值分类）=====
        Entry("自身在地上", listOf("自身在地上", "self.gound"), Reason.NON_KEY),
        Entry("自身在卸载单位", listOf("自身在卸载单位", "self.isTransportUnloading"), Reason.NON_KEY),
        Entry("事件数据", listOf("事件数据", "eventData"), Reason.NON_KEY),
        Entry("自身最大速度", listOf("自身最大速度", "self.maxspeed"), Reason.NON_KEY),
        Entry("自身连接数", listOf("自身连接数", "self.numberOfConnections"), Reason.NON_KEY),
        Entry("调试通行", listOf("调试通行", "debugPassthrough"), Reason.NON_KEY),
        Entry("行动索引/索引", listOf("行动索引/索引", "thisActionIndex/index"), Reason.NON_KEY),
        Entry("敌对队伍", listOf("敌对队伍", "aggressiveTeam"), Reason.NON_KEY),

        // ===== C. 名字不是合法键（选中会插入废键）=====
        Entry(
            "defineUnitMemory: boolean nukeActive, boolean laserReady, float experience, " +
                "unit nextTarget, unit homeBase, string customText",
            listOf(
                "defineUnitMemory: boolean nukeActive, boolean laserReady, float experience, " +
                    "unit nextTarget, unit homeBase, string customText"
            ),
            Reason.ILLEGAL_NAME
        ),
        Entry(
            "defineUnitMemory: unit[] squad, float[] mango",
            listOf("defineUnitMemory: unit[] squad, float[] mango"),
            Reason.ILLEGAL_NAME
        ),
        Entry("\${X}", listOf("\${X}"), Reason.ILLEGAL_NAME),
        Entry("\${section.key}", listOf("\${section.key}"), Reason.ILLEGAL_NAME),
        Entry("%{X}", listOf("%{X}"), Reason.ILLEGAL_NAME)
    )

    /** 结尾参数括号：`self.gound()` → `self.gound`、`debugPassthrough(LogicBoolean)` → `debugPassthrough`。 */
    private val TRAILING_PARENS = Regex("""\(.*\)$""")

    /** 规范化：去首尾空白 + 去掉结尾的 `(...)`，使表内写法与翻译库 key（已去括号）能一致比对。 */
    private fun normalize(name: String): String = name.trim().replace(TRAILING_PARENS, "").trim()

    /** 默认名单展开后的比对集合。 */
    private val defaultNameSet: Set<String> = DEFAULT_ENTRIES
        .flatMap { it.names }
        .map { normalize(it) }
        .filter { it.isNotEmpty() }
        .toSet()

    /** 用户改动（相对默认名单的增删）。 */
    @Serializable
    private data class Config(
        /** 默认项里被用户放出的条目（按 normalize 后的名字记录） */
        val removedDefaults: List<String> = emptyList(),
        /** 用户自行追加的隐藏条目 */
        val extraEntries: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private var loaded = false
    private var config = Config()

    private var cachedEffective: Set<String>? = null
    private var cachedRevision = -1

    /** 配置版本号：每次增删 / 恢复默认 +1，编辑器据此重建补全 Provider。 */
    var revision by mutableStateOf(0)
        private set

    private fun configFile(): File? = try {
        RwmodPaths.completionBlocklistFile
    } catch (_: Exception) {
        // 单测环境（无 Android 存储）下拿不到路径，退化为纯内存名单
        null
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        config = readConfig()
        loaded = true
    }

    private fun readConfig(): Config = try {
        val f = configFile()
        if (f != null && f.exists()) json.decodeFromString<Config>(f.readText()) else Config()
    } catch (_: Exception) {
        Config()
    }

    private fun persist() {
        try {
            configFile()?.atomicWriteText(json.encodeToString(config), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun bumpLocked() {
        cachedEffective = null
        revision++
    }

    private fun effectiveSetLocked(): Set<String> {
        ensureLoadedLocked()
        cachedEffective?.let { if (cachedRevision == revision) return it }
        val removed = config.removedDefaults.mapTo(HashSet()) { normalize(it) }
        val set = HashSet<String>(defaultNameSet.size + config.extraEntries.size)
        defaultNameSet.filterTo(set) { it !in removed }
        config.extraEntries.forEach {
            val n = normalize(it)
            if (n.isNotEmpty()) set.add(n)
        }
        cachedEffective = set
        cachedRevision = revision
        return set
    }

    /** 任一名（原始 name / nameEn / label，或翻译库 key）命中隐藏名单即返回 true。 */
    fun isBlocked(vararg candidates: String): Boolean {
        val effective = synchronized(lock) { effectiveSetLocked() }
        return candidates.any { it.isNotBlank() && normalize(it) in effective }
    }

    /** 默认项当前是否处于隐藏状态（用户放出后为 false）。 */
    fun isDefaultHidden(entry: Entry): Boolean {
        val names = entry.names.map { normalize(it) }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return true
        synchronized(lock) {
            ensureLoadedLocked()
            val removed = config.removedDefaults.mapTo(HashSet()) { normalize(it) }
            // 只要还有一个写法没被放出，就算仍处于隐藏状态
            return names.any { it !in removed }
        }
    }

    /** 设置默认项是否隐藏（false = 放回补全候选）。 */
    fun setDefaultHidden(entry: Entry, hidden: Boolean) {
        val names = entry.names.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked()
            val removed = config.removedDefaults.map { normalize(it) }.toMutableSet()
            if (hidden) {
                removed.removeAll(names)
            } else {
                removed.addAll(names)
            }
            config = config.copy(removedDefaults = removed.toList())
            persist()
            bumpLocked()
        }
    }

    /** 用户追加的隐藏条目（原始输入顺序）。 */
    fun userEntries(): List<String> = synchronized(lock) {
        ensureLoadedLocked()
        config.extraEntries.toList()
    }

    /** 追加一条隐藏项；空白或已生效（重复）时返回 false。 */
    fun addExtra(raw: String): Boolean {
        val name = raw.trim()
        if (name.isEmpty()) return false
        val norm = normalize(name)
        if (norm.isEmpty()) return false
        synchronized(lock) {
            ensureLoadedLocked()
            val exists = norm in effectiveSetLocked() ||
                config.extraEntries.any { normalize(it) == norm }
            if (exists) return false
            config = config.copy(extraEntries = config.extraEntries + name)
            persist()
            bumpLocked()
            return true
        }
    }

    /** 删除一条用户追加的隐藏项；默认项请用 [setDefaultHidden]。 */
    fun removeExtra(raw: String) {
        val norm = normalize(raw)
        synchronized(lock) {
            ensureLoadedLocked()
            val next = config.extraEntries.filterNot { normalize(it) == norm }
            if (next.size == config.extraEntries.size) return
            config = config.copy(extraEntries = next)
            persist()
            bumpLocked()
        }
    }

    /** 清空所有用户改动，回到内置默认名单。 */
    fun resetToDefault() {
        synchronized(lock) {
            ensureLoadedLocked()
            config = Config()
            persist()
            bumpLocked()
        }
    }

    /** 是否存在用户改动（界面据此决定「恢复默认」是否可用）。 */
    fun hasUserChanges(): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        config.removedDefaults.isNotEmpty() || config.extraEntries.isNotEmpty()
    }
}
