package com.rwmodstudio.feature.completion.value

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

    /** 开火音效英文属性名（type=string，非项目音频引用，供 isShootSound 判定） */
    private val shootSoundName = "shoot_sound"

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (request.isInsideParentheses()) return false
        val prop = request.findProperty() ?: return false
        if (isShootSound(prop)) return true
        return projectRefKind(prop.type) != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prop = request.findProperty() ?: return emptyList()
        // 开火音效：type=string，非项目音频引用 —— 内置音效 + 项目音频（支持 .wav/.ogg/ROOT: 自定义）并存
        if (isShootSound(prop)) {
            return buildItems(request, "音效", (builtInSounds + request.soundFiles).distinct(), descPrefix = "data.value.sound")
        }
        val kind = projectRefKind(prop.type) ?: return emptyList()
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
            // sound 类（sound ref）：内置音效 + 项目音频文件并存
            "sound" -> (builtInSounds + request.soundFiles).distinct()
            else -> return emptyList()
        }
        val descPrefix = if (kind == "effect") "data.value.effect" else if (kind == "sound") "data.value.sound" else null
        return buildItems(request, "项目引用", source, descPrefix = descPrefix)
    }

    private fun isShootSound(prop: com.rwmodstudio.core.translation.CodeReferenceRepository.PropertyInfo): Boolean {
        return prop.name.equals(shootSoundName, ignoreCase = true) ||
            prop.name_en.equals(shootSoundName, ignoreCase = true)
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
                // 内置音效/特效（builtInSounds/builtInEffects）逐项查中文说明；项目音频/效果节名等无说明项回退默认 detail
                val perName = descPrefix?.let { ValueDescriptionRepository.lookup(request.context, "$it.$name") }
                createValueItem(
                    label = name,
                    detail = perName ?: detail,
                    insertText = name,
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