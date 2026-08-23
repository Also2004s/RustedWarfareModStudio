package com.rwmodstudio.core.translation

import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 补全类型分类仓库（单例）。
 *
 * 将代码参考库全部属性归类到「补全类型分类」，供开发者模式「补全类型分类」页展示与维护。
 *
 * 归类原则（用户定论）：**不看 type**——type 数据不可靠（同样 type 可能属不同补全、
 * 裸 type 无法区分生成/标记/名字）。分类归属统一由「真实补全判定」给出：
 * 与生产补全完全相同的一组 Provider 逐个 matches、首中即定（见
 * ValueCompletionAggregator.realCompletionClassify），**不存在第二份名单**，永不漂移。
 * 本仓库只保留：分类常量/展示顺序、manual 覆盖、合成条目（不在 code_reference 的指令属性）。
 *
 * 分类 = 调用源（哪个补全代码服务该属性）：
 * - 13 个属性类：布尔值/逻辑表达式/枚举值/图片路径/事件触发/内存变量/标签/资源/
 *   单位名（含生成类）/单位标记/项目引用/复制节/文字任意（兜底）；
 * - 1 个参数类（值类）：作用于值的语法，列出【值里能写 函数名(xxx) 带参数（如 int(a,b)）】的属性，
 *   以证据硬编码的信息卡展示（触发/补全/位置）。
 *
 * 分两层：
 * - manual：用户在维护页手工覆盖的属性→分类映射（写 RWmod/cache/completion_types.json，
 *   验证码变化或文件缺失时重置为空）。
 */
object CompletionTypeRepository {
    private const val TAG = "CompletionTypeRepository"

    // ===== 一级分类：属性类（13） =====
    const val CAT_BOOL = "布尔值"
    const val CAT_LOGIC = "逻辑表达式"
    const val CAT_ENUM = "枚举值"
    const val CAT_IMAGE = "图片路径"
    const val CAT_EVENT = "事件触发"
    const val CAT_MEMORY = "内存变量"
    const val CAT_TAG = "标签"
    const val CAT_RESOURCE = "资源"
    const val CAT_UNIT_NAME = "单位名"
    const val CAT_UNIT_MARKER = "单位标记"
    const val CAT_PROJECT_REF = "项目引用"
    const val CAT_COPY_SECTION = "复制节"
    const val CAT_TEXT = "文字/任意"

    // ===== 一级分类：参数类（1，作用于值的语法：值里能写 函数名(xxx) 带参数，如 int(a,b)） =====
    const val CAT_PARAM = "参数类"

    /** 属性类展示顺序 */
    val PROPERTY_CATEGORY_ORDER = listOf(
        CAT_BOOL, CAT_LOGIC, CAT_ENUM, CAT_IMAGE, CAT_EVENT,
        CAT_MEMORY, CAT_TAG, CAT_RESOURCE, CAT_UNIT_NAME, CAT_UNIT_MARKER,
        CAT_PROJECT_REF, CAT_COPY_SECTION, CAT_TEXT
    )

    /** 值语法类展示顺序 */
    val SYNTAX_CATEGORY_ORDER = listOf(CAT_PARAM)

    /** 全部分类展示顺序 = 属性类 + 值语法类 */
    val CATEGORY_ORDER = PROPERTY_CATEGORY_ORDER + SYNTAX_CATEGORY_ORDER

    /** 动态资源属性（用逻辑设置/添加资源：RHS 为逻辑表达式，值里可写带参函数） */
    val DYNAMIC_RESOURCE_PROPERTIES = setOf("addresourceswithlogic", "setresourceswithlogic")

    /** 参数类 owner 属性类：这些类属性的【值】能写成 函数名(xxx) 带调用（如 int(a,b)、distance(,)） */
    private val PARAMETER_OWNER_CATEGORIES =
        setOf(CAT_LOGIC, CAT_UNIT_MARKER, CAT_MEMORY, CAT_EVENT, CAT_UNIT_NAME)

    /**
     * 判定某属性是否为「参数类」成员（分类页成员列表 + 统计共用）。
     * 参数类 = 该属性的【值】里能写 `函数名(参数)` 带括号调用（如 int(a,b)、rnd(0,n)、distance(x,y)）。
     * @param propCategory 该属性的属性类（classify 结果）
     * @param nameEn 属性英文名（小写匹配）
     */
    fun isParameterMember(propCategory: String, nameEn: String): Boolean {
        val en = nameEn.lowercase()
        if (en in DYNAMIC_RESOURCE_PROPERTIES) return true
        return propCategory in PARAMETER_OWNER_CATEGORIES
    }

    /**
     * 参数类信息（证据硬编码：触发语法 / 补全内容 / 有据可查的可用位置）。
     * 语法作用于值：值里能写 函数名(xxx) 带括号调用。
     */
    data class SyntaxInfo(
        val trigger: String,    // 触发语法
        val completes: String,  // 补全内容
        val positions: String   // 有据可查的可用位置（证据硬编码）
    )

    val PARAM_INFO = SyntaxInfo(
        trigger = "值中可写 函数名(xxx) 括号带参与调用（如 int(a,b)、rnd(0,n)、distance(,)）",
        completes = "按函数参数表补全参数名（参数=）；纯位置参数函数（选择/rnd/int/distance 等）按参数位置类型补逻辑表达式",
        positions = "逻辑表达式值、单位标记表达式（创建标记(x=之间方向(self, 接近单位(...)))）、内存变量 RHS、自动触发事件（tookDamage(需标签=)）、产生单位/刷抛射体括号（证据：需要条件、可见性、添加路径点来自参考、产生单位、用逻辑设置/添加资源、发送消息到）"
    )

    /**
     * 分类 → 调用源（补全代码 + 触发语法/内容一句话），分类页每个分类标题下展示。
     */
    val CATEGORY_SOURCE = mapOf(
        CAT_BOOL to "BoolValueCompletionProvider：真/假",
        CAT_LOGIC to "LogicBooleanValueCompletionProvider：逻辑表达式（含 dynamic/kvp/fields 的 = 后）",
        CAT_ENUM to "EnumValueCompletionProvider：枚举值",
        CAT_IMAGE to "ImageValueCompletionProvider：项目图片路径",
        CAT_EVENT to "AutoTriggerOnEventValueCompletionProvider：事件",
        CAT_MEMORY to "MemoryValueCompletionProvider：内存变量名/类型",
        CAT_TAG to "TagValueCompletionProvider：标签（本地/全局/消息）",
        CAT_RESOURCE to "ResourceValueCompletionProvider：资源名（资源名=值）",
        CAT_UNIT_NAME to "UnitNameValueCompletionProvider + UnitSpawnCompletionProvider：项目单位名/生成示例",
        CAT_UNIT_MARKER to "UnitRefValueCompletionProvider：单位标记表达式",
        CAT_PROJECT_REF to "ProjectRefValueCompletionProvider：炮塔/抛射体/效果/行动/动画/贴花/附属/可建造/音频节名",
        CAT_COPY_SECTION to "CopyFromSectionValueCompletionProvider：同类型命名节名",
        CAT_TEXT to "兜底：无补全 Provider 命中（手填数值/文本）",
        CAT_PARAM to "FunctionParameterCompletionProvider / LogicBooleanValueCompletionProvider 括号分支：值里能写 函数名(xxx) 带参（int(a,b)、rnd(0,n)、distance(,)）"
    )

    fun getFile(): java.io.File = RwmodPaths.completionTypesFile

    /** manual 覆盖：属性名 → 分类 */
    private var manual: Map<String, String> = emptyMap()
    private var loaded = false

    /**
     * 加载 manual 覆盖（IO 线程调用）。文件缺失或验证码不匹配时重置为空。
     */
    suspend fun load() {
        loaded = true
        val file = getFile()
        try {
            if (!file.exists()) { manual = emptyMap(); return }
            if (SettingsManager.readVerifyCode(SettingsManager.VERIFY_COMPLETION_TYPES) !=
                SettingsManager.COMPLETION_TYPES_VERIFY_CODE
            ) { manual = emptyMap(); return }
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val arr = json.optJSONArray("manual") ?: JSONArray()
            val map = linkedMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val cat = o.optString("category")
                if (name.isNotEmpty() && cat.isNotEmpty()) map[name] = cat
            }
            manual = map
            Log.d(TAG, "Loaded ${map.size} manual overrides")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load completion types, manual empty", e)
            manual = emptyMap()
        }
    }

    /**
     * 保存 manual 覆盖（IO 线程调用）。写入后刷新验证码，下次不再重置。
     */
    fun saveManual(overrides: Map<String, String>): Boolean {
        return try {
            manual = overrides
            val file = getFile()
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            overrides.forEach { (name, cat) ->
                arr.put(JSONObject().apply { put("name", name); put("category", cat) })
            }
            file.writeText(JSONObject().apply { put("manual", arr) }.toString(2), Charsets.UTF_8)
            SettingsManager.writeVerifyCode(
                SettingsManager.VERIFY_COMPLETION_TYPES,
                SettingsManager.COMPLETION_TYPES_VERIFY_CODE
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save completion types", e)
            false
        }
    }

    /**
     * 清空 manual 覆盖并删除持久化文件，分类回到真实补全判定（供维护页「重置」使用）。
     */
    fun resetManual() {
        manual = emptyMap()
        try {
            val f = getFile()
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset completion types manual", e)
        }
    }

    /**
     * 合成条目：不在 code_reference 但真实存在且触发补全的指令属性（翻译库收录）。
     * 分类页在 code_reference 属性之外补充展示。
     */
    data class SyntheticProperty(val name: String, val nameEn: String)

    val SYNTHETIC_PROPERTIES = listOf(
        SyntheticProperty("复制节", "@copyFromSection"),           // → 复制节
        SyntheticProperty("复制但跳过节", "@copyFrom_skipThisSection") // → 布尔值（真/假）
    )

    fun isLoaded(): Boolean = loaded
    fun currentManual(): Map<String, String> = manual
}
