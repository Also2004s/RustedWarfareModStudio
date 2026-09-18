package com.rwmodstudio.core.translation

import com.rwmodstudio.core.KeyTemplateRegistry

/**
 * 补全共享判定源（单一数据源）。
 *
 * 「真实补全（feature/completion/value/ 各 Provider）」与「补全类型分类（CompletionTypeRepository，
 * 分类页 + 模拟ini）」共用同一份英文名名单/判定函数，杜绝两套名单漂移
 * （曾出现 spawnProjectiles* 在分类页被误归【单位名】/【项目引用】来回改的历史）。
 *
 * 分类归属原则（用户定论）：不看 type——type 不可靠，一律以硬编码英文名名单为准。
 * 本对象即这些名单的权威出处；Provider 与分类页都从这里取。
 * 纯 type 判定（bool/enum/memory/logic/image/projectRefKind 等，仅 Provider 使用、分类页不改写）
 * 不搬到这里，留在各 Provider 内。
 */
object CompletionShared {

    // ===== 单位/抛射体生成（UnitSpawnCompletionProvider） =====

    /** 单位生成类属性（值填单位名，如 spawnUnits/produceUnits 等） */
    val unitSpawnProperties = setOf(
        "spawnunits", "spawnunit", "produceunits",
        "addunitsintotransport", "attachments_addnewunits"
    )

    /** 抛射体生成类属性（值填抛射体节引用，如 spawnProjectilesOnCreate 等；分类侧归【项目引用】） */
    val projectileSpawnProperties = setOf(
        "spawnprojectiles", "spawnprojectile",
        "spawnprojectilesoncreate", "spawnprojectilesonendoflife", "spawnprojectilesonexplode"
    )

    /** 是否为抛射体生成属性（英文名判定，忽略大小写） */
    fun isProjectileSpawnProperty(nameEn: String): Boolean =
        nameEn.lowercase() in projectileSpawnProperties

    /**
     * 是否为「单位/抛射体生成」属性判定（英文名，忽略大小写）。
     * 单位生成(spawnUnits 等)值填单位名，归【单位名】；抛射体生成(spawnProjectiles*)值填抛射体节引用，
     * 归【项目引用】。此处 Provider 作运行时服务判定；分类归属由 CompletionTypeRepository 组合本名单决定。
     */
    fun isUnitSpawnProperty(nameEn: String): Boolean =
        nameEn.lowercase() in unitSpawnProperties || isProjectileSpawnProperty(nameEn)

    // ===== 单位名列表（UnitNameValueCompletionProvider） =====

    /** 单位名列表固定成员（isUnitNameListProperty 的固定名单部分） */
    val unitNameListProperties = setOf(
        "unitsspawnedondeath", "overrideandreplace", "upgradedfrom", "displaylocalekey"
    )

    /** 序号模板键 builtFrom_{NUM}_name 的模板名（小写），真实文件里写 builtFrom_1_name */
    private const val BUILT_FROM_NAME_TEMPLATE = "builtfrom_{num}_name"

    /** Registry 尚未注册时（早期调用 / JVM 单测）的兜底判定，兼容实例化与空位形态 */
    private val BUILT_FROM_NAME_FALLBACK = Regex("""builtFrom_\d*_name""", RegexOption.IGNORE_CASE)

    /**
     * 判断英文属性名是否为「单位名列表」属性（语义核对后登记，供 JVM 单测）。
     * 匹配：unitsSpawnedOnDeath / overrideAndReplace / upgradedFrom / builtFrom_{NUM}_name
     * （含实例化 builtFrom_1_name 与未填数字的空位形态 builtFrom__name）。
     * 不匹配：altNames / onNewMapSpawn / displayName / 标签类 / builtFrom_1_pos 等。
     *
     * 序号模板键统一由 KeyTemplateRegistry 归一化，不再逐条硬编码正则——模板集来自补全表，
     * 以后新增同类键（canBuild_#_xxx 等）也走同一机制。
     */
    fun isUnitNameListProperty(enName: String): Boolean {
        if (enName.lowercase() in unitNameListProperties) return true
        if (KeyTemplateRegistry.normalize(enName).lowercase() == BUILT_FROM_NAME_TEMPLATE) return true
        return BUILT_FROM_NAME_FALLBACK.matches(enName)
    }

    /** 触发单位名补全的归一化 type 关键词（已去空格） */
    val unitNameTypeKeywords = setOf("unitref", "unitreftype", "unittype", "unittypes")

    /**
     * 是否为「单位名」type 判定（typeLower 转小写，忽略空格，如 "unit ref"→"unitref"）。
     * 供 UnitNameValueCompletionProvider 复用（分类页已按名单固化，不走 type）。
     */
    fun isUnitNameTypeMatch(typeLower: String): Boolean {
        val norm = typeLower.lowercase().replace(" ", "")
        return unitNameTypeKeywords.any { norm.contains(it) }
    }

    // ===== 单位标记（UnitRefValueCompletionProvider） =====

    /** 单位引用表达式语义的 "unit ref" 属性（值填单位标记表达式，如 self.父单位/最后伤害源） */
    val unitRefExpressionProperties = setOf(
        "setcustomtarget1", "setcustomtarget2", "sendmessageto",
        "transporttargetnow", "addwaypoint_target_fromreference",
        "takeresources_includereference",
        "teleportto", "fireturretxatground_withtarget",
        "alsotriggerorqueueactionwithtarget"
    )

    /** 标记/位置表达式属性（marker ref / 纯位置，如 baseposition/drawLineTo） */
    val unitMarkerPositionProperties = setOf("baseposition", "drawlineto")

    /** 混合型属性（既接受单位名也接受表达式，如 unitshowninui/textAddUnitName/descriptionAddFromUnit/descriptionAddUnitStats） */
    val unitMarkerMixedProperties = setOf("unitshowninui", "textaddunitname", "descriptionaddfromunit", "descriptionaddunitstats")

    /** 是否为单位引用表达式属性（英文名，忽略大小写） */
    fun isUnitRefExpressionProperty(nameEn: String): Boolean =
        nameEn.lowercase() in unitRefExpressionProperties

    /** 是否为位置/标记表达式属性（英文名，忽略大小写） */
    fun isUnitMarkerPositionProperty(nameEn: String): Boolean =
        nameEn.lowercase() in unitMarkerPositionProperties

    /** 是否为混合型单位标记属性（英文名，忽略大小写） */
    fun isUnitMarkerMixedProperty(nameEn: String): Boolean =
        nameEn.lowercase() in unitMarkerMixedProperties

    // ===== 内存（MemoryValueCompletionProvider） =====

    /** 内存属性精确名单（英文小写，经 code_reference 逐条核实真实用法）。
     *  仅这些属性本身触发内存补全；含 "memory" 子串但非内存引用的
     *  updateUnitMemoryRate(更新单位内存频率,type=int) 不在此列，杜绝误触发。 */
    val memoryPropertyNames = setOf(
        "setunitmemory", "updateunitmemory", "defineunitmemory", "@memory"
    )

    /** 是否为内存属性（英文名，忽略大小写） */
    fun isMemoryProperty(nameEn: String): Boolean = nameEn.lowercase() in memoryPropertyNames

    /**
     * 是否为「定义单位内存」声明键（`定义单位内存: 类型 变量名, 类型 变量名`）。
     * 该键的值里**空格是「类型」与「变量名」之间的合法分隔**，不是"换下一个值"的分隔符
     * （换下一个值只认逗号），故值片段切分需对它与众不同（见 replaceableValuePrefix）。
     * 中文键名与英文键名都认（code_reference 里该条目的 name 就是整条声明模板，故按原样比对）。
     */
    fun isDefineUnitMemoryProperty(name: String): Boolean {
        val n = name.trim().lowercase()
        return n == "defineunitmemory" || n == "定义单位内存"
    }

    // ===== 复制节（CopyFromSectionValueCompletionProvider） =====

    /** 是否为 @copyFromSection（复制节）属性。英文 nameEn 为基础（调用方可经翻译引擎反查得到），
     *  中文「复制节」兜底（该词在翻译库，属翻译库形式）。 */
    fun isCopyFromSectionProperty(propertyName: String, nameEn: String?): Boolean =
        nameEn?.lowercase() == "@copyfromsection" ||
            propertyName.lowercase() == "@copyfromsection" ||
            propertyName == "复制节"

    /** 是否为 @copyFrom_skipThisSection（复制但跳过节）属性。英文为基础，中文「复制但跳过节」兜底（翻译库形式）。 */
    fun isCopyFromSkipSectionProperty(propertyName: String, nameEn: String?): Boolean =
        nameEn?.lowercase() == "@copyfrom_skipthissection" ||
            propertyName.lowercase() == "@copyfrom_skipthissection" ||
            propertyName == "复制但跳过节"

    /** 是否为「复制与」(copyFrom) 属性。核心节的 copyFrom 是 ini 文件路径（由 ProjectRefValueCompletionProvider
     *  处理并归「项目引用」）；其他节（腿/炮塔/效果等）的 copyFrom 引用同类节的节后缀名
     *  （由 CopyFromSectionValueCompletionProvider 补节名并归「复制节」）。英文为基础，中文「复制与」兜底（翻译库形式）。 */
    fun isCopyFromProperty(propertyName: String, nameEn: String?): Boolean {
        val p = propertyName.lowercase().replace(" ", "")
        val e = nameEn?.lowercase()?.replace(" ", "")
        return p == "copyfrom" || p == "复制与" || e == "copyfrom"
    }

    /** 是否为核心节（当前真实节名归一化后等于 中文「核心」或 英文 `core`）。供「核心节复制与=路径、其他节=节名」分流使用。 */
    fun isCoreSection(sectionName: String?): Boolean {
        val s = sectionName?.trim()?.lowercase()?.substringBefore('_')
        return s == "核心" || s == "core"
    }

    /** 翻译引擎当前是否启用（开关打开且已加载）。补全项「显示与插入值」的语言总闸：
     *  开→中文（走翻译库），关→英文（不硬编码中文，显示源码字面）。 */
    fun translationEngineEnabled(): Boolean =
        com.rwmodstudio.core.SettingsManager.devTranslationEngine &&
            TranslationEngine.getInstance().isLoaded

    /** 屏蔽词开关「引号内的词不翻译」当前是否生效（总开关也需打开，默认关）。
     *  生效时引擎把引号内内容整段占位保护（TranslationBlocklist.protectQuotedDictWords）：
     *  既不翻成中文、也不反译回英文。故该开关打开时，**引号包裹的补全值只能用英文**，
     *  否则写进去的中文会永久留在文件里。见 FunctionParameterCompletionProvider 的场景闸。 */
    fun quotedValuesBlocked(): Boolean =
        TranslationEngine.getInstance().getBlocklist().let { it.enabled && it.blockQuotedDictWords }

    // ===== 标签（TagValueCompletionProvider） =====

    /** 标签属性精确名单（英文小写，经 code_reference 逐条核实真实用法、用户审核）。
     *  覆盖全部 25 个标签属性（本地/全局/消息标签）；含 mutatorX_ 前缀通配（修正需要标签等）。
     *  type 只作初筛参考：onlyUseAsHarvester_ifBaseHasUnitTagged 虽为 string 类型但用法是标签，
     *  也在此名单内（type 不匹配但用法匹配 → 硬编码注入，杜绝漏触发）。
     *  注意：mutatorX_ 名单项需经 [isTagProperty] 通配判定，勿直接用 `in` 比较实际 mutator<数字>_ 键。 */
    val tagProperties = setOf(
        "addglobalteamtags", "addwaypoint_target_nearestunit_tagged", "addwaypoint_target_randomunit_tagged", "canonlybeattackedbyunitswithtags",
        "canonlyattackunitswithouttags", "canonlyattackunitswithtags", "canreclaimresourcesonlywithtags", "canreclaimunitsonlywithtags",
        "canrepairunitsonlywithtags", "deletenumunitsfromtransport_onlywithtags", "interceptprojectiles_withtags", "mutatorx_ifunitwithouttags",
        "mutatorx_ifunitwithtags", "onlyuseasharvester_ifbasehasunittagged", "removeglobalteamtags", "retargetinginflightsearchonlytags", "searchtags",
        "sendmessagewithtags", "showactionswithmixedselectionifotherunitshavetag", "similarresourceshavetag", "tags",
        "takeresources_excludeunitswithouttags", "temporarilyaddtags", "temporarilyremovetags", "transportunitsrequiretag"
    )

    /** 全局标签属性（值来自项目全局标签 request.globalTags；仅这两者，不含其它含 "global" 子串的非标签属性） */
    val globalTagProperties = setOf("addglobalteamtags", "removeglobalteamtags")

    /** 是否为标签属性（英文名，忽略大小写；支持 mutatorX_ 名单项通配实际 mutator<数字>_ 前缀） */
    fun isTagProperty(nameEn: String): Boolean {
        val n = nameEn.lowercase()
        if (n in tagProperties) return true
        return tagProperties.any { key ->
            key.startsWith("mutatorx_") &&
                Regex("^mutator[0-9]+_" + Regex.escape(key.substringAfter("mutatorx_"))).matches(n)
        }
    }

    /** 是否为全局标签属性（英文名，忽略大小写） */
    fun isGlobalTagProperty(nameEn: String): Boolean = nameEn.lowercase() in globalTagProperties

    // ===== 资源（ResourceValueCompletionProvider） =====

    /** 资源属性精确名单（英文小写，经 code_reference 逐条核实真实用法、用户审核）。
     *  覆盖全部 20 个资源属性（价格/增加资源/用逻辑资源/取资源等）；含 mutatorX_ 前缀通配（修正直接添加资源等）。
     *  注意：mutatorX_ 名单项需经 [isResourceProperty] 通配判定，勿直接用 `in` 比较实际 mutator<数字>_ 键。 */
    val resourceProperties = setOf(
        "addresources", "addresourcesscaledbyaihandicaps", "addresourceswithlogic", "appendresourceinhud",
        "borrowresourceswhilealive", "borrowresourceswhilebuilt", "convertresource_from", "convertresource_to",
        "displaytextappendresource", "generation_resources", "mutatorx_addresourcesareahit", "mutatorx_addresourcesdirecthit",
        "price", "resourceamount", "resourceamount_addotherresource", "resourceusage",
        "setresourceswithlogic", "streamingcost", "takeresources", "takeresources_excludeunitswiththeseresources"
    )

    /** 是否为资源属性（英文名，忽略大小写；支持 mutatorX_ 通配） */
    fun isResourceProperty(nameEn: String): Boolean {
        val n = nameEn.lowercase()
        if (n in resourceProperties) return true
        return resourceProperties.any { key ->
            key.startsWith("mutatorx_") &&
                Regex("^mutator[0-9]+_" + Regex.escape(key.substringAfter("mutatorx_"))).matches(n)
        }
    }

    // ===== 单位名（UnitNameValueCompletionProvider / VanillaUnitNameValueCompletionProvider） =====

    /** 单位名固定属性（英文小写，经 code_reference 逐条核实真实用法、用户审核）。
     *  不含单位名列表（unitsSpawnedOnDeath 等）与单位生成（spawnUnits 等），那些由各自名单判定。
     *  convertto/onCreateSpawnUnitOf 等虽 type 为 "unit ref" 但用法是单位类型名，已在此名单（type 不匹配但用法匹配）。 */
    val unitNameProperties = setOf(
        "addwaypoint_unittype", "convertto",
        "guibuildunit", "name", "oncreatespawnunitof",
        "whenbuilding_temporarilyconvertto"
    )

    /** 是否为单位名固定属性（英文名，忽略大小写） */
    fun isUnitNameProperty(nameEn: String): Boolean = nameEn.lowercase() in unitNameProperties
}