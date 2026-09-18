package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.CompletionShared
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 项目内引用名补全。
 * 按属性 type 关键字给对应引用名：turret ref→炮塔节名、projectile ref→抛射体节名、
 * effect ref→效果节名、action refs→行动（含隐藏行动）节名、animation ref→动画节名、
 * decal refs→贴花节名、attachment ref→附属节名、canBuild→可建造节名、sound ref→项目音频文件。
 * 数据来源为 ProjectTagScanner 收集的命名节（当前文件 + 继承链 + 项目缓存三源合并）。
 * 开火动画（effects）与开火音效（shoot_sound）在项目无对应命名节/音频时，
 * 回退到各自的内置词表（词表来自代码参考表 开火动画/开火音效 的 desc，并经真实项目用法确认）。
 * 暂不做 v1：`CUSTOM:` 与 `*数量` 后缀提示。
 */
class ProjectRefValueCompletionProvider : BaseValueCompletionProvider() {

    /** 内置特效词表（与代码参考表 开火动画.desc 一致，effect 类属性均可使用内置特效） */
    private val builtInEffects = listOf(
        "small", "large", "smoke", "shockwave",
        "medium", "largeExplosion", "smallExplosion", "resourcePoolSmoke", "noneExplosion"
    )

    /** 内置音效词表（与代码参考表 开火音效.desc 一致，sound 类属性均可填入文件名或内置音效） */
    private val builtInSounds = listOf(
        "attack", "move", "click", "missile_fire", "missile_hit", "unit_explode", "buiding_explode",
        "tank_firing", "cannon_firing", "gun_fire", "lighting_burst", "plasma_fire", "plasma_fire2",
        "firing3", "firing4", "large_gun_fire1", "large_gun_fire2", "bug_die", "bug_attack",
        "interface_error", "nuke_explode", "nuke_launch", "laser_deflect", "laser_deflect2"
    )

    /**
     * 音效路径属性硬编码精确名单（经 code_reference.json 逐条核实真实用法，type 只作初筛）。
     * 覆盖：action/effect 的 sound ref 播放声音、core 的 sound(s) 指令/选中/死亡音效，
     * 以及 type 不匹配但用法确为音效的 shoot_sound(string)/soundOnDeath(string(s))。
     * 排除项（名字或 type 像音效但实际非路径，由名单天然隔离）：
     * hitSound(打击音效,bool 开关)、shoot_sound_vol(开火音效大小,float)、shoot_flame(开火动画,effects)、shoot_light(开火闪光,color)。
     */
    private val soundPathNames = setOf(
        // action/effect：sound ref 播放声音
        "playSoundToPlayerOnQueue", "playSoundAtUnit", "playSoundGlobally", "playSoundToPlayer",
        "alsoPlaySound",
        // core：sound(s) 指令/选中/死亡音效
        "soundOnAttackOrder", "soundOnMoveOrder", "soundOnNewSelection",
        // type 不匹配但用法是音效
        "shoot_sound", "soundOnDeath"
    )

    /** 音效多语言变体前缀（playSoundToPlayer_zh 等，属性名后加 _{LANG}），同样按音效补全 */
    private val soundNamePrefixes = listOf(
        "playsoundtoplayeronqueue_", "playsoundatunit_", "playsoundglobally_", "playsoundtoplayer_"
    )

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (request.isInsideParentheses()) return false
        val prop = request.findProperty() ?: return false
        if (isSoundProperty(prop)) return true
        // 复制与(copyFrom)：仅核心节参与 ini 文件路径补全；其他节（腿/炮塔/效果等）交
        // CopyFromSectionValueCompletionProvider 补节名，这里对非核心节复制与一律不响应
        // （避免 projectRefKind 兜底把炮塔节 reproduction 之类 type=turret ref 的复制与误归路径）
        if (CompletionShared.isCopyFromProperty(request.propertyName, request.toEnglishName())) {
            return isCopyFromFile(prop, request)
        }
        return projectRefKind(prop.type) != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prop = request.findProperty() ?: return emptyList()
        // 音效：内置音效 + 项目音频（默认同目录裸名、跨目录需 ROOT:）并存
        if (isSoundProperty(prop)) {
            return buildSoundItems(request)
        }
        // 核心节 复制与(copyFrom)：type=file(s) (ini)，项目 ini/template 文件（默认同目录裸名、跨目录需 ROOT:）
        // canProvide 已保证只对核心节复制与进入到此分支，provideItems 再按本节口径复核一次
        if (isCopyFromFile(prop, request)) {
            return buildFileItems(request)
        }
        val kind = projectRefKind(prop.type) ?: return emptyList()
        // 打了空格 / Tab：空格不是合法分隔符——
        // 多值引用（effect/action/attachment/projectile）→ 只弹「,」（选完吞掉空格成 `a,b`）；
        // 单值引用（turret/decal/animation）→ 值已写完，不弹。
        if (needsCommaSeparator(request)) {
            return if (kind in multiValueRefKinds) {
                listOf(commaSeparatorItem("项目引用分隔符（值与值之间用逗号分隔）"))
            } else {
                emptyList()
            }
        }
        val source = when (kind) {
            "turret" -> request.turretNames
            "projectile" -> request.projectileNames
            // effect 类（effects / effect / effect ref / effect(s) ref）：内置特效始终可选，项目效果节并存
            "effect" -> (builtInEffects + request.effectNames).distinct()
            "action" -> request.actionNames + request.hiddenActionNames
            "animation" -> request.animationNames
            "decal" -> request.decalNames
            "attachment" -> request.attachmentNames
            "canbuild" -> request.buildableNames
            // sound 类（sound ref）音频：内置音效 + 项目音频（默认同目录裸名、跨目录需 ROOT:）
            "sound" -> return buildSoundItems(request)
            else -> return emptyList()
        }
        val descPrefix = if (kind == "effect") "data.value.effect" else if (kind == "sound") "data.value.sound" else null
        // CUSTOM: 前缀：effect 支持 CUSTOM:<项目效果节名>（如 [效果_xxx] 的 xxx），不匹配内置效果节名
        if (kind == "effect" && prefixIsCustomPrefixed(request.valuePrefix)) {
            val customNames = request.effectNames
                .map { it.removePrefix("CUSTOM:").removePrefix("custom:") }
                .filter { it.isNotBlank() }
            return buildItems(request, "项目引用", customNames.map { "CUSTOM:$it" }, descPrefix = descPrefix)
        }
        return buildItems(request, "项目引用", source, descPrefix = descPrefix)
    }

    /** valuePrefix 是否以 CUSTOM: 开头 */
    private fun prefixIsCustomPrefixed(prefix: String): Boolean =
        prefix.lowercase().startsWith("custom:")

    /**
     * 可按逗号填多个的引用类别（依据 code_reference 的官方示例 + 真实项目用法）：
     * 效果（死亡效果: shockwave, CUSTOM:pieces*3）、行动（也执行动作: addCredits, playSound）、
     * 附属槽位（附属添加于槽位: drop1, drop2）、抛射体（在寿命结束时刷抛射体: a(...), b(...)）。
     * 单值引用（炮塔/贴花/动画/可建造）不在其中：官方示例全为单值，不提示逗号。
     */
    private val multiValueRefKinds = setOf("effect", "action", "attachment", "projectile")

    /**
     * 音效路径属性判定：硬编码名单精确匹配 + type 初筛兜底。
     * 名单优先（含多语言变体前缀）；未命中名单时用 type 初筛（sound ref/sound(s)/sounds），
     * 但会规避 bool/float/effects/color 等非路径类型。
     */
    private fun isSoundProperty(prop: com.rwmodstudio.core.translation.CodeReferenceRepository.PropertyInfo): Boolean {
        val names = listOfNotNull(prop.name, prop.name_en).map { it.trim().lowercase() }
        // 精确名单命中（含英文 name_en 与中文 name 反查场景）
        if (names.any { it in soundPathNames }) return true
        // 多语言变体前缀命中（playSoundToPlayer_zh 等）
        if (names.any { n -> soundNamePrefixes.any { n.startsWith(it) } }) return true
        // type 初筛兜底：归一化后含 soundref/sounds 才走音效，bool/float/effects/color 天然隔离
        return projectRefKind(prop.type) == "sound"
    }

    /** 核心节 复制与(copyFrom)：type 为 file(s) (ini) 等文件引用，**且当前节是核心节** 才走 ini 文件路径补全。
     * 其他节（腿=int / 炮塔=turret ref / 效果等命名节）的 copyFrom 引用其他节，交 CopyFromSectionValueCompletionProvider
     * 补节名，本项目不补路径。sectionName 为 null（分类页/跨节判定）时按核心节处理，保持 copyFrom 归「项目引用」。 */
    private fun isCopyFromFile(
        prop: com.rwmodstudio.core.translation.CodeReferenceRepository.PropertyInfo,
        request: ValueCompletionRequest
    ): Boolean {
        val isCopyFrom = prop.name.equals("copyFrom", ignoreCase = true) ||
            prop.name_en.equals("copyFrom", ignoreCase = true)
        if (!isCopyFrom || !prop.type.lowercase().contains("file")) return false
        // 严格只认核心节：只有核心节复制与走 ini 文件路径；其他节（腿/炮塔/效果等）交 CopyFromSectionValueCompletionProvider 补节名。
        return CompletionShared.isCoreSection(request.sectionName)
    }

    /**
     * 音效补全：内置音效 + 项目音频。与图片路径补全同规则——
     * 默认（未输入/未点 ROOT:）只列同目录项目音频（裸名）+ 内置音效；
     * 输入/点击 ROOT: 后才列出跨目录项目音频（ROOT:/相对路径）。
     */
    private fun buildSoundItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // 多值音效（攻击/移动/选择指令音效，官方说明写明"用,隔开"）：打了空格 → 只提示「,」
        if (needsCommaSeparator(request)) {
            return listOf(commaSeparatorItem("音效分隔符（值与值之间用逗号分隔）"))
        }
        val prefix = request.valuePrefix
        val inRoot = prefix.trim().lowercase().startsWith("root:")
        // ROOT: 态用「去掉 ROOT: 前缀」的子串；默认态用输入子串
        val sub = if (inRoot) prefix.trim().lowercase().removePrefix("root:").removePrefix("/").trim()
        else prefix.trim().lowercase()
        // 全量项目音频（相对路径，不带 ROOT:），补全侧自行过滤，不依赖预滤波集合
        val allProjectSounds = ProjectSoundCache.query(request.projectRoot, "", limit = 500)

        val raw = mutableListOf<Pair<String, String>>() // (insert, sortKey)
        // 默认态：提供 ROOT: 前缀选择项，点击/输入后展开跨目录项目音效
        if (!inRoot && request.projectRoot.isNotBlank()) raw += "ROOT:" to "ROOT:"
        // 内置音效：仅默认态显示（按子串匹配）；ROOT: 态只列跨目录项目音频，内置音效不掺入
        builtInSounds.forEach { name ->
            if (inRoot) return@forEach
            if (sub.isEmpty() || name.contains(sub, ignoreCase = true)) raw += name to name
        }
        // 项目音频：默认只同目录裸名；ROOT: 态只列跨目录 ROOT:/ 相对路径
        allProjectSounds.forEach { rel ->
            val insert = formatProjectRefInsertValue(rel, request.currentFileDir, request.projectRoot)
            val isCrossDir = insert.startsWith("ROOT:/")
            if (inRoot != isCrossDir) return@forEach // inRoot→保留跨目录; 默认→保留同目录
            val base = rel.substringAfterLast('/').substringAfterLast('\\')
            if (sub.isEmpty() || rel.contains(sub, true) || base.contains(sub, true)) raw += insert to insert
        }
        val items = raw.sortedWith(compareBy<Pair<String, String>> { !it.second.contains(sub) }.thenBy { it.second.lowercase() })
            .map { (insert, _) ->
                val perName = ValueDescriptionRepository.lookup(request.context, "data.value.sound.$insert")
                createValueItem(
                    label = insert,
                    detail = perName ?: "音效",
                    insertText = insert,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "string"
                )
            }
            .toMutableList()
        // 默认态把 ROOT: 前缀选择项置顶（与图片补全一致），便于用户直接点选展开跨目录项目音效
        if (!inRoot) {
            items.firstOrNull { it.insertText == "ROOT:" }?.let {
                items.remove(it)
                items.add(0, it)
            }
        }
        return items
    }

    /**
     * 核心节 复制与(copyFrom) 的文件补全：项目 ini/template 文件。与图片/音效路径补全同规则——
     * 默认（未输入/未点 ROOT:）只列同目录项目文件（裸名）；
     * 输入/点击 ROOT: 后才列出跨目录项目文件（ROOT:/相对路径），且 ROOT: 前缀选项默认置顶。
     */
    private fun buildFileItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        // 复制与 可一次抄多个文件（`复制与: ROOT:xx.template, yy.ini`）：打了空格 → 只提示「,」
        if (needsCommaSeparator(request)) {
            return listOf(commaSeparatorItem("文件分隔符（值与值之间用逗号分隔）"))
        }
        val prefix = request.valuePrefix
        val inRoot = prefix.trim().lowercase().startsWith("root:")
        // ROOT: 态用「去掉 ROOT: 前缀」的子串；默认态用输入子串
        val sub = if (inRoot) prefix.trim().lowercase().removePrefix("root:").removePrefix("/").trim()
        else prefix.trim().lowercase()
        // 全量项目 ini/template 文件（相对路径，不带 ROOT:），补全侧自行过滤
        val allProjectFiles = ProjectIniFileCache.query(request.projectRoot, "", limit = 500)

        val raw = mutableListOf<Pair<String, String>>() // (insert, sortKey)
        // 默认态：提供 ROOT: 前缀选择项，点击/输入后展开跨目录项目文件
        if (!inRoot && request.projectRoot.isNotBlank()) raw += "ROOT:" to "ROOT:"
        // 项目文件：默认只同目录裸名；ROOT: 态只列跨目录 ROOT:/ 相对路径
        allProjectFiles.forEach { rel ->
            val insert = formatProjectRefInsertValue(rel, request.currentFileDir, request.projectRoot)
            val isCrossDir = insert.startsWith("ROOT:/")
            if (inRoot != isCrossDir) return@forEach // inRoot→保留跨目录; 默认→保留同目录
            val base = rel.substringAfterLast('/').substringAfterLast('\\')
            if (sub.isEmpty() || rel.contains(sub, true) || base.contains(sub, true)) raw += insert to insert
        }
        val items = raw.sortedWith(compareBy<Pair<String, String>> { !it.second.contains(sub) }.thenBy { it.second.lowercase() })
            .map { (insert, _) ->
                createValueItem(
                    label = insert,
                    detail = "ini/template 文件",
                    insertText = insert,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "string"
                )
            }
            .toMutableList()
        // 默认态把 ROOT: 前缀选择项置顶（与图片/音效补全一致），便于用户直接点选展开跨目录项目文件
        if (!inRoot) {
            items.firstOrNull { it.insertText == "ROOT:" }?.let {
                items.remove(it)
                items.add(0, it)
            }
        }
        return items
    }

    private fun buildItems(
        request: ValueCompletionRequest,
        detail: String,
        names: Collection<String>,
        descPrefix: String? = null
    ): List<CompletionProvider.CompletionItem> {
        if (names.isEmpty()) return emptyList()
        val prefix = request.valuePrefix
        return names
            .map { name ->
                // 路径值（sounds 等）支持按文件名（去目录）匹配：sfx/confirm.wav 输入 confirm 命中，其余按整名分级匹配
                val base = name.substringAfterLast('/').substringAfterLast('\\')
                val level = if (prefix.isEmpty()) 2 else maxOf(completionMatchLevel(prefix, name), completionMatchLevel(prefix, base))
                name to level
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .map { it.first }
            .map { name ->
                // 项目音频路径（含目录分隔符）格式化为「同目录裸名、跨目录 ROOT:/」；内置音效名保持不变
                val isProjectPath = name.contains('/') || name.contains('\\')
                val insert = if (isProjectPath) formatProjectRefInsertValue(name, request.currentFileDir, request.projectRoot) else name
                // 内置音效/特效（builtInSounds/builtInEffects）逐项查中文说明；项目音频/效果节名等无说明项回退默认 detail
                val perName = descPrefix?.let { ValueDescriptionRepository.lookup(request.context, "$it.$name") }
                createValueItem(
                    label = insert,
                    detail = perName ?: detail,
                    insertText = insert,
                    prefixLength = request.rawValuePrefixLength,
                    valueType = "string"
                )
            }
    }
}

/**
 * 属性 type → 引用名类别。先归一化再匹配，未命中返回 null。
 * 归一化：小写、去空格、`(s)`→`s`、`ids`→`id`，
 * 使 `effect(s) ref` / `action ids` / `animation id` / `attachment ids` 等写法均可命中。
 */
internal fun projectRefKind(rawType: String): String? {
    val type = rawType.lowercase()
        .replace(" ", "")
        .replace("(s)", "s")    // effect(s) ref -> effectsref
        .replace("ids", "id")   // action ids -> actionid
    return when {
        type == "turret" || type.contains("turretref") || type == "turrets" -> "turret"
        type.contains("projectileref") || type == "projectiles" -> "projectile"
        type.contains("effectref") || type == "effect" || type == "effects" || type == "effectsref" -> "effect"
        type.contains("actionref") || type == "actions" || type == "actionid" -> "action"
        type.contains("animationref") || type == "animationid" -> "animation"
        type.contains("decalref") || type == "decals" -> "decal"
        type.contains("attachmentref") || type == "attachmentid" -> "attachment"
        type == "canbuild" || type.contains("canbuildref") -> "canbuild"
        type.contains("soundref") || type == "sounds" -> "sound"
        else -> null
    }
}