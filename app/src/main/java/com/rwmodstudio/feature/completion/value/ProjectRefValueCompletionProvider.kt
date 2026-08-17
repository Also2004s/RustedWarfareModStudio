package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 项目内引用名补全。
 * 按属性 type 关键字给对应引用名：turret ref→炮塔节名、projectile ref→抛射体节名、
 * effect ref→效果节名、action refs→行动（含隐藏行动）节名、animation ref→动画节名、
 * decal refs→贴花节名、attachment ref→附属节名、canBuild→可建造节名、sound ref→项目音频文件。
 * 数据来源为 ProjectTagScanner 收集的命名节（当前文件 + 继承链 + 项目缓存三源合并）。
 * 不做 v1：CUSTOM: 内置效果与 `*数量` 后缀提示。
 */
class ProjectRefValueCompletionProvider : BaseValueCompletionProvider() {

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        if (request.isInsideParentheses()) return false
        val prop = request.findProperty() ?: return false
        return projectRefKind(prop.type) != null
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prop = request.findProperty() ?: return emptyList()
        val kind = projectRefKind(prop.type) ?: return emptyList()
        val source = when (kind) {
            "turret" -> request.turretNames
            "projectile" -> request.projectileNames
            "effect" -> request.effectNames
            "action" -> request.actionNames + request.hiddenActionNames
            "animation" -> request.animationNames
            "decal" -> request.decalNames
            "attachment" -> request.attachmentNames
            "canbuild" -> request.buildableNames
            "sound" -> request.soundFiles
            else -> return emptyList()
        }
        if (source.isEmpty()) return emptyList()

        val prefix = request.valuePrefix
        return source
            .map { name ->
                // sound 路径支持按文件名（去目录）匹配：sfx/confirm.wav 输入 confirm 命中，其余按整名分级匹配
                val base = name.substringAfterLast('/').substringAfterLast('\\')
                val level = if (prefix.isEmpty()) 2 else maxOf(completionMatchLevel(prefix, name), completionMatchLevel(prefix, base))
                name to level
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .map { it.first }
            .map { name ->
                createValueItem(
                    label = name,
                    detail = "项目引用",
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