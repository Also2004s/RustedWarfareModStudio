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
 * 裸 type 无法区分生成/标记/名字），一律以**硬编码英文名名单**为准；
 * 名单由 tools/gen_completion_types.js 按各补全 Provider 的判定逻辑
 * 从 code_reference.json 一次性生成（首中即定、互斥），审核后嵌入此处。
 * 数据或判定变更后重跑脚本整体替换名单；单个属性归类调整直接挪名单即可。
 *
 * 分类 = 调用源（哪个补全代码服务该属性）：
 * - 13 个属性类：布尔值/逻辑表达式/枚举值/图片路径/事件触发/内存变量/标签/资源/
 *   单位名（含生成类）/单位标记/项目引用/复制节/文字任意（兜底）；
 * - 1 个参数类（值类）：作用于值的语法，列出【值里能写 函数名(xxx) 带参数（如 int(a,b)）】的属性，
 *   以证据硬编码的信息卡展示（触发/补全/位置）。
 *
 * 分两层：
 * - 硬编码名单（本文件）：审核后的归类事实，运行时唯一判定依据；
 * - manual：用户在维护页手工覆盖的属性→分类映射（写 RWmod/cache/completion_types.json，
 *   验证码变化或文件缺失时重置回硬编码名单）。
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
     * 加载 manual 覆盖（IO 线程调用）。文件缺失或验证码不匹配时重置为空（全走硬编码名单）。
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
     * 清空 manual 覆盖并删除持久化文件，完全回到硬编码名单（供维护页「重置」使用）。
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
     * 对指定属性归类（**只查名单，不看 type**）。manual 覆盖优先，否则硬编码名单，
     * 全部未命中落兜底「文字/任意」（分类页统计可见，便于发现漏归档）。
     * @param name 中文属性名（或无英文时的原名）
     * @param nameEn 英文属性名（名单键，小写匹配）
     */
    fun classify(name: String, nameEn: String?): String {
        manual[name]?.let { return it }
        val en = (nameEn ?: name).lowercase()
        // 复制节 / 复制但跳过节（合成指令）：与 CopyFromSectionValueCompletionProvider / Bool 同源
        if (CompletionShared.isCopyFromSectionProperty(name, nameEn)) return CAT_COPY_SECTION
        return when {
            // 复制但跳过（合成指令，Bool 服务补 真/假）
            CompletionShared.isCopyFromSkipSectionProperty(name, nameEn) -> CAT_BOOL
            en in EVENT_PROPERTIES -> CAT_EVENT
            en in MEMORY_PROPERTIES -> CAT_MEMORY
            // 单位名：单位生成(非抛射体) + 单位名列表 + 其余 type 驱动的单位名属性
            // （与 UnitSpawn/UnitName/Vanilla Provider 共源，规则见 CompletionShared）
            ((CompletionShared.isUnitSpawnProperty(en) && !CompletionShared.isProjectileSpawnProperty(en)) ||
                CompletionShared.isUnitNameListProperty(en) || en in UNIT_NAME_PROPERTIES) -> CAT_UNIT_NAME
            // 单位标记：纯表达式单位标记 + 位置 + 混合型（与 UnitRef Provider 共源，见 CompletionShared）
            (CompletionShared.isUnitRefExpressionProperty(en) ||
                CompletionShared.isUnitMarkerPositionProperty(en) ||
                CompletionShared.isUnitMarkerMixedProperty(en)) -> CAT_UNIT_MARKER
            en in RESOURCE_PROPERTIES -> CAT_RESOURCE
            en in ENUM_PROPERTIES -> CAT_ENUM
            en in LOGIC_PROPERTIES -> CAT_LOGIC
            // 项目引用：抛射体生成(spawnProjectiles*) + 其余 type 驱动的节引用
            // （与 UnitSpawn/ProjectRef Provider 共源，见 CompletionShared）
            (CompletionShared.isProjectileSpawnProperty(en) || en in PROJECT_REF_PROPERTIES) -> CAT_PROJECT_REF
            en in BOOL_PROPERTIES -> CAT_BOOL
            en in IMAGE_PROPERTIES -> CAT_IMAGE
            en in TAG_PROPERTIES -> CAT_TAG
            else -> CAT_TEXT
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

    // ===== 以下名单由 tools/gen_completion_types.js 从 code_reference.json 生成（勿手改名单本身，
    //      数据或判定变更后重跑脚本再整体替换）；运行时只做名单查找，不按 type 推断 =====
    // 漂移高危区（单位生成/抛射体生成/单位名列表/单位标记/复制节）已下沉 [CompletionShared]，
    // 由 classify 调用其判定函数统一接管，本文件仅保留剩余「非共享、type 驱动」名单。

    /** 布尔值 分类属性（键=英文小写）；@copyfrom_skipthissection 由共享判定接管 */
    val BOOL_PROPERTIES = setOf(
        "action_#_whenbuilding_cannotmove", "addtransportedunits", "addwaypoint_position_fromaction", "addwaypoint_prepend",
        "addwaypoint_target_mapmustbereachable", "ai_considersameasbuilding", "allowcapturewhenneutralbyai", "allowmultipleinqueue",
        "alwaysallowedtoattackparentsmaintarget", "alwayssinglepress", "alwaysstartdiratzero", "alwaystartdiratzero",
        "alwaysvisibleinfog", "animateframelooping", "animation_type_pingpong", "appendresourceinhud_whenthiszero",
        "areadamagenofalloff", "areahitairandlandatsametime", "areahitunderwateralways", "arearadiusfromedge",
        "atmospheric", "attachedtounit", "attachments_disconnect", "attachments_unload",
        "autorepair", "autotargetingondeadtarget", "autotriggercheckwhilenotbuilt", "autotriggercooldowntime_allowdangeroushighcpu",
        "ballistic", "beamimageendrotated", "beamimagestartrotated", "blocksplacement",
        "builtfrom_{num}_forcenano", "canattack", "canbeattackedanddamaged", "canbuild_#_islocked",
        "cannotbedamaged", "cannotbedirectlyattacked", "cannotbegivenordersbyplayer", "canplayercancel",
        "canreclaimresources", "canrepairbuildings", "canrepairunits", "canshoot",
        "checkeachtile", "clearactivewaypoint", "clearallactioncooldowns", "clearallwaypoints",
        "clearturrettargetafterfiring", "converthittosourceteam", "convertto_keepcurrenttags", "converttoneutralifnottransporting",
        "createincompleteifparentis", "createneutral", "createonaggressiveteam", "createwhenoffscreen",
        "createwhenoverland", "createwhenoverliquid", "createwhenzoomedout", "deattachifwantingtomove",
        "deleteself", "dieonattack", "dieonconstruct", "dieonzeroenergy",
        "disableallunitcollisions", "disabledeathonzerohp", "disableleadtargeting", "disablelowhpfire",
        "disablelowhpsmoke", "disablepassivetargeting", "disableuse", "disconnectfromparent",
        "displaycoloruseintext", "displayinhud", "displaynamehidewheniconshowninhud", "displaynamehidewheniconshownintext",
        "displayremainingstockpile", "displayroundeddown", "displaytextappendresourcewithgap", "displaywhenzero",
        "displaywithrounding", "dont_load", "draw_foot_on_top", "drawfootwhenzoomedout",
        "drawlegwhenzoomedout", "drawoverbody", "drawunderallunits", "drawunderunits",
        "dust_effect", "dusteffect", "dusteffectreverse", "energyneedstorechargetofull",
        "equivalentglobalresourceforai", "excludeincompletebuildings", "excludenonbuildings", "exitheightignoreparent",
        "experimental", "explodeondeath", "explodeondeathgroundcollision", "explodeonendoflife",
        "fadeout", "favouroppositesideneighbours", "finishplayinglastanimation", "flameweapon",
        "forcenano", "forceunloadtransportnow", "frameindexrandom", "friendlyfire",
        "hidden", "hidescorchmark", "highpriorityqueue", "hitsound",
        "hold_moveonlyiffurthest", "holddismin_checkneighbours", "icon_zoomed_out_nevershow", "iconimageuseintext",
        "ignoreinunitcapcalculation", "ignoreparentshootdamagemultiplier", "image_applyteamcolors", "image_end_teamcolors",
        "image_shadow_frames", "includeinstats", "includeparentsselection", "instant",
        "instantreuselast", "instantreuselast_alsochangeturretaim", "instantreuselast_keepareadamagelist", "interceptprojectile_removetargetlifeonly",
        "invisible", "isalsoviewablebyallies", "isalsoviewablebyenemies", "isbio",
        "isbug", "isbuilder", "isbuilding", "isfixedfiring",
        "islockedifgamemodenonuke", "ismainnanoturret", "ismelee", "ispickablestartingunit",
        "isunrepairableunit", "isunselectable", "isunselectableastarget", "isvisibletoenemies",
        "joinsgroupformations", "keepalivewhenparentdies", "keepwaypointsneedingmovement", "landonground",
        "largehiteffect", "lasereffect", "lightcastonground", "lightingeffect",
        "liveafterattacheddies", "lock_body_rotation_with_main_turret", "lock_leg_rotation_with_main_turret", "lock_shadow_rotation_with_main_turret",
        "locklegmovement", "lockmovement", "lockrotation", "lowprioritytargetforotherunits",
        "moveignoringbody", "movementeffectreverseflipeffects", "moveslidingmode", "movewithparent",
        "nanorangeforreclaimismelee", "nanorangeforrepairismelee", "notpassivelytargetedbyotherunits", "nukeondeath",
        "nukeondeathdisablewhennonuke", "nukeweapon", "onconvertkeepexistingunitinsameslot", "onlyinpreview",
        "onlyoneunitatatime", "onlyonnonpreview", "onlyplayerswithunitcontrol", "onlywhenselectedbyallynotownplayer",
        "onlywhenselectedbyanyplayer", "onlywhenselectedbyenemyplayer", "onlywhenselectedbyownplayer", "onlywhileactive",
        "onlywhilealive", "onparentteamchangekeepcurrentteam", "physics", "pingpong",
        "placeonlyonrespool", "playanimationifnotplaying", "prioritizeparentsmaintarget", "redirectdamagetoparent",
        "redirectdamagetoparent_shieldonly", "refundallqueueditems", "removeallqueueditemswithoutrefund", "resetrotationwhennotattacking",
        "resettodefaulttags", "resetunitstats", "retargetinginflight", "rotate_with_direction",
        "rotatewithparent", "setdrawlayeronbottom", "setdrawlayerontop", "shadow",
        "shielddisplayonlydeflection", "shouldresetturret", "shouldrevealfog", "showenergybar",
        "showhealthbar", "showineditor", "showinfog", "showonminimap",
        "showonminimaptoenemies", "showqueuebar", "showrangeuiguide", "showselectionindicator",
        "showshieldbar", "showshotdelaybar", "showtransportbar", "slave",
        "slowdeathfall", "smoothlyblendpositionwhenexistingunitadded", "splasteffect", "splasteffectreverse",
        "stack_drawinreverseorder", "stackhorizontal", "startfallingwhenstartingunit", "startshieldatzero",
        "startunloadingtransport", "stayneutral", "stoplastanimation", "stoptargetingafterfiring",
        "switchpricewithstreamingcost", "switchtoaggressiveteam", "switchtoneutralteam", "takeresources_directtransferstoppingatzero",
        "takeresources_discardcollected", "takeresources_excludeunitswithoutallresources", "takeresources_includeparent", "takeresources_includeunitsintransport",
        "takeresources_keepresourcesontarget", "takeresources_searchonly", "targetground", "targetground_includetargetheight",
        "targetheightrelative", "teamcolors", "teamcolorsonturret", "teamcolorsusehue",
        "teleportsource", "traileffect", "transportunitsaddunloadoption", "transportunitsblockairandwaterunits",
        "transportunitsblockothertransports", "transportunitseachunitalwaysusessingleslot", "transportunitskeepbuiltunits", "transportunitsonteamchangekeepcurrentteam",
        "turretmultitargeting", "turretrotatewithbody", "unitshowninuiwithhpbar", "unitshowninuiwithprogressbar",
        "unitsspawnedondeath_settoteamoflastattacker", "unloadincurrentposition", "useasbuilder", "useasharvester",
        "useastransport", "valueinstats", "warmupnoreset", "whenbuilding_cannotmove",
        "whenbuilding_rotateto_aimatactiontarget", "whenbuilding_rotateto_orbackwards", "whenbuilding_rotateto_waittillrotated", "whenusingasharvester_includeotherharvestercounts",
        "whileneutralconverttotransportedteam", "whileneutraltransportanyteam"
    )

    /** 逻辑表达式 分类属性（37 个，键=英文小写） */
    val LOGIC_PROPERTIES = setOf(
        "ai_isdisabled", "ai_ishighpriority", "alpha", "alsotriggeractionrepeat",
        "alsotriggerorqueueactionconditional", "altprojectilecondition", "autotrigger", "builtfrom_{num}_islocked",
        "canattackcondition", "canattackflyingunits", "canattacklandunits", "canattacknottouchingwaterunits",
        "canattackunderwaterunits", "frame", "generation_active", "iconextraisvisible",
        "idlesweepcondition", "imagescale", "imagescalex", "imagescaley",
        "isactive", "isguiblinking", "islocked", "islockedalt",
        "islockedalt2", "isvisible", "requireconditional", "resetcustomtimer",
        "setheight", "showallactionsfrom", "stack_indexcount", "stack_indexstart",
        "switchtoteam", "transportunitscanunloadunits", "transportunitskillondeath", "xoffsetabsolute",
        "yoffsetabsolute"
    )

    /** 枚举值 分类属性（22 个，键=英文小写） */
    val ENUM_PROPERTIES = setOf(
        "addwaypoint_target_nearestunit_team", "addwaypoint_target_randomunit_team", "addwaypoint_type", "attackmovement",
        "autotriggercheckrate", "convertto_keepcurrentfields", "displaydigitgrouping", "displaytype",
        "drawlayer", "drawtype", "explodetypeondeath", "fireturretxatground_onlyoverpassabletileof", "layer",
        "movementtype", "onactions", "onlyteam", "onnewmapspawn",
        "searchteam", "setunitstats", "stripindex", "takeresources_includeunitswithinrange_team", "teamcoloringmode",
        "transportunitsrequiremovementtype", "whenbuilding_temporarilyconvertto_keepfields"
    )

    /** 图片路径 分类属性（22 个，键=英文小写） */
    val IMAGE_PROPERTIES = setOf(
        "beamimage", "beamimageend", "beamimagestart",
        "chargeeffectimage", "icon_build", "icon_zoomed_out", "iconextraimage",
        "iconimage", "image", "image_back", "image_end",
        "image_end_shadow", "image_foot", "image_foot_shadow", "image_leg",
        "image_middle", "image_shadow", "image_shield", "image_turret",
        "image_wreak", "imageshadow", "imagestack"
    )

    /** 事件触发 分类属性（1 个，键=英文小写） */
    val EVENT_PROPERTIES = setOf(
        "autotriggeronevent"
    )

    /** 内存变量 分类属性（5 个，键=英文小写） */
    val MEMORY_PROPERTIES = setOf(
        "@memory", "defineunitmemory", "setunitmemory", "updateunitmemory",
        "updateunitmemoryrate"
    )

    /** 标签 分类属性（24 个，键=英文小写） */
    val TAG_PROPERTIES = setOf(
        "addglobalteamtags", "addwaypoint_target_nearestunit_tagged", "addwaypoint_target_randomunit_tagged", "canonlybeattackedbyunitswithtags",
        "canonlyattackunitswithouttags", "canonlyattackunitswithtags", "canreclaimresourcesonlywithtags", "canreclaimunitsonlywithtags",
        "canrepairunitsonlywithtags", "deletenumunitsfromtransport_onlywithtags", "interceptprojectiles_withtags", "mutatorx_ifunitwithouttags",
        "mutatorx_ifunitwithtags", "removeglobalteamtags", "retargetinginflightsearchonlytags", "searchtags",
        "sendmessagewithtags", "showactionswithmixedselectionifotherunitshavetag", "similarresourceshavetag", "tags",
        "takeresources_excludeunitswithouttags", "temporarilyaddtags", "temporarilyremovetags", "transportunitsrequiretag"
    )

    /** 资源 分类属性（20 个，键=英文小写） */
    val RESOURCE_PROPERTIES = setOf(
        "addresources", "addresourcesscaledbyaihandicaps", "addresourceswithlogic", "appendresourceinhud",
        "borrowresourceswhilealive", "borrowresourceswhilebuilt", "convertresource_from", "convertresource_to",
        "displaytextappendresource", "generation_resources", "mutatorx_addresourcesareahit", "mutatorx_addresourcesdirecthit",
        "price", "resourceamount", "resourceamount_addotherresource", "resourceusage",
        "setresourceswithlogic", "streamingcost", "takeresources", "takeresources_excludeunitswiththeseresources"
    )

    /** 单位名 分类剩余属性（键=英文小写；仅保留非共享、type 驱动的单位名属性）。
     *  共享部分（单位生成 spawnUnits 等 / 单位名列表 unitsSpawnedOnDeath 等）已下沉 [CompletionShared]，
     *  由 classify 调用其 isUnitSpawnProperty/isUnitNameListProperty 判定。 */
    val UNIT_NAME_PROPERTIES = setOf(
        "addwaypoint_unittype", "convertto",
        "descriptionaddunitstats", "guibuildunit", "name", "oncreatespawnunitof",
        "whenbuilding_temporarilyconvertto"
    )

    /** 项目引用 分类属性（55 个，键=英文小写；含抛射体引用 spawnProjectiles*、projectile 等） */
    val PROJECT_REF_PROPERTIES = setOf(
        "addactioncooldownapplytoactions", "addwaypoint_triggeractioniffailed", "addwaypoint_triggeractionifmatched", "alsoemiteffects",
        "alsoemiteffectsondeath", "alsoplaysound", "alsoqueueaction", "alsotriggeraction",
        "altprojectile", "attachedto", "attachments_onlyonslots", "basepositionfromturret",
        "effectoncreate", "effectondeath", "effectondeathgroundcollision", "effectondeathifunbuilt",
        "explodeeffect", "explodeeffectonshield", "fireturretxatground", "fireturretxatground_showguidedecals",
        "fireturretxatground_withprojectile", "fireturretxatselfondeath", "ifspawnfailsemiteffects", "linkdelaywithturret",
        "movementeffect", "movementeffectreverse", "mutatorx_changedexplodeeffect", "onshoot_playanimation",
        "onshoot_triggeractions", "playanimation", "playsoundatunit", "playsoundglobally",
        "playsoundtoplayer", "playsoundtoplayeronqueue", "projectile", "reclaimeffect",
        "reclaimeffectattarget", "repaireffect", "repaireffectattarget", "shoot_flame",
        "soundonattackorder", "soundonmoveorder", "soundonnewselection", "spawneffects",
        "spawneffectsonqueue",
        "takeresources_triggeractionforeach", "takeresources_triggeractionifanycollected", "takeresources_triggeractionifnonecollected",
        "warmupstarteffect", "whenbuilding_playanimation", "whenbuilding_rotateto_rotateturretx", "whenbuilding_triggeraction"
    )
}
