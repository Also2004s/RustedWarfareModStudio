package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.feature.completion.CompletionProvider

/**
 * 图片路径值补全。
 * 对应 VS Code 插件的 ImageValueCompletionProvider。
 * 读取 [ProjectImageCache] 缓存的图片路径，返回 ROOT:/ 相对路径。
 * 中文属性名通过翻译库反向查找判别。
 */
class ImageValueCompletionProvider : BaseValueCompletionProvider() {

    private val imagePropertyHints = setOf(
        "image", "iconImage", "image_", "icon_", "teamColoringImage",
        "imageScale", "imageOffsetX", "imageOffsetY"
    )

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        val prop = request.findProperty() ?: return false
        val names = listOfNotNull(prop.name, prop.name_en)
        val lowerNames = names.joinToString(" ").lowercase()
        val lowerType = prop.type.lowercase()

        // 英文匹配
        if (names.any { it.lowercase() in imagePropertyHints } ||
            lowerNames.contains("image") ||
            lowerType.contains("image") ||
            lowerType.contains("path")) return true

        // 中文属性名通过翻译库反向查找
        val propNameLower = request.propertyName.lowercase()
        if (propNameLower.contains("image")) return true
        if (request.isChineseName()) {
            val enName = request.toEnglishName().lowercase()
            if (enName.contains("image") || enName in imagePropertyHints) return true
            if (request.propertyName.contains("图像") || request.propertyName.contains("图标")) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val projectPath = SettingsManager.defaultPath.takeIf { it.isNotBlank() }
            ?: SettingsManager.lastPath.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val prefix = request.valuePrefix
        val paths = ProjectImageCache.query(projectPath, prefix, limit = 50)

        return paths.map {
            createValueItem(
                label = it,
                detail = "图片路径",
                insertText = it,
                prefixLength = request.rawValuePrefixLength,
                valueType = "string"
            )
        }
    }
}
