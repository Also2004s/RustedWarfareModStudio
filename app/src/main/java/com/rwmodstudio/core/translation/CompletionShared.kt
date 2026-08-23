package com.rwmodstudio.core.translation

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
        "unitsspawnedondeath", "overrideandreplace", "upgradedfrom"
    )

    /**
     * 判断英文属性名是否为「单位名列表」属性（语义核对后登记，供 JVM 单测）。
     * 匹配：unitsSpawnedOnDeath / overrideAndReplace / upgradedFrom / builtFrom_{NUM}_name。
     * 不匹配：altNames / onNewMapSpawn / displayName / 标签类 / builtFrom_1_pos 等。
     */
    fun isUnitNameListProperty(enName: String): Boolean =
        enName.lowercase() in unitNameListProperties ||
            Regex("""builtFrom_\d+_name""", RegexOption.IGNORE_CASE).matches(enName)

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

    /** 混合型属性（既接受单位名也接受表达式，如 unitshowninui/textAddUnitName/descriptionAddFromUnit） */
    val unitMarkerMixedProperties = setOf("unitshowninui", "textaddunitname", "descriptionaddfromunit")

    /** 是否为单位引用表达式属性（英文名，忽略大小写） */
    fun isUnitRefExpressionProperty(nameEn: String): Boolean =
        nameEn.lowercase() in unitRefExpressionProperties

    /** 是否为位置/标记表达式属性（英文名，忽略大小写） */
    fun isUnitMarkerPositionProperty(nameEn: String): Boolean =
        nameEn.lowercase() in unitMarkerPositionProperties

    /** 是否为混合型单位标记属性（英文名，忽略大小写） */
    fun isUnitMarkerMixedProperty(nameEn: String): Boolean =
        nameEn.lowercase() in unitMarkerMixedProperties

    // ===== 复制节（CopyFromSectionValueCompletionProvider） =====

    /** 是否为 @copyFromSection（复制节）属性：name_en 或 propertyName 归一化为 @copyfromsection，或中文 复制节 */
    fun isCopyFromSectionProperty(propertyName: String, nameEn: String?): Boolean =
        nameEn?.lowercase() == "@copyfromsection" ||
            propertyName.lowercase() == "@copyfromsection" ||
            propertyName == "复制节"

    /** 是否为 @copyFrom_skipThisSection（复制但跳过节）属性 */
    fun isCopyFromSkipSectionProperty(propertyName: String, nameEn: String?): Boolean =
        nameEn?.lowercase() == "@copyfrom_skipthissection" ||
            propertyName.lowercase() == "@copyfrom_skipthissection" ||
            propertyName == "复制但跳过节"
}