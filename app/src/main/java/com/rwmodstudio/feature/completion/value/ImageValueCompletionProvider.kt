package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 图片路径值补全。
 * 对应 VS Code 插件的 ImageValueCompletionProvider。
 * 读取 [ProjectImageCache] 缓存的图片路径，返回 ROOT:/ 相对路径。
 * 中文属性名通过翻译库反向查找判别。
 */
class ImageValueCompletionProvider : BaseValueCompletionProvider() {

    /**
     * 图片路径属性硬编码精确名单（经 code_reference.json 逐条核实真实用法）。
     * 只对真正接受图片文件路径的属性触发补全；含 image 子串但为数值/布尔等非路径属性
     * （如 imageScaleX 图像X缩放、iconImageUseInText 在文本中使用图标）由 [imageTypeExcludeNames] 隔离。
     * 覆盖各节真实图片路径属性：图像(graphics)/腿(leg_arm)/抛射体(projectile)/炮塔(turret) 等，
     * 以及 type 不匹配但用法确为图片路径的 resource.iconImage（type=String）。
     */
    private val imagePathNameSet = setOf(
        // 各节通用主图/阴影/堆叠
        "image", "imageShadow", "image_shadow", "imageStack",
        "iconImage", "iconExtraImage",
        // 图像(graphics)节
        "image_back", "image_shield", "image_wreak", "image_turret",
        "icon_zoomed_out", "icon_build",
        // 腿(leg_arm)节
        "image_end", "image_end_shadow", "image_foot", "image_foot_shadow",
        "image_middle", "image_leg",
        // 抛射体(projectile)节
        "beamImage", "beamImageStart", "beamImageEnd",
        // 炮塔(turret)节
        "chargeEffectImage"
    )

    /**
     * type 初筛命中（file (image)/image/images）但实际非图片路径的排除名单。
     * 这些属性名含 image 或看似图片类型，实为数值/布尔/颜色等：
     * imageScale/图像X缩放/图像Y缩放、图像偏移(image_offset*)、缩放(scaleImagesTo/scaleTurretImagesTo)、
     * 阴影帧开关(image_shadow_frames)、末端图像着色(image_end_teamColors)、波束偏移/旋转、
     * 炮塔队伍色/绘制偏移、iconImageUseInText(在文本中使用图标)。
     */
    private val imageTypeExcludeNames = setOf(
        "imagescale", "imagescalex", "imagescaley",
        "image_offsetx", "image_offsety", "image_offseth", "image_floatingpointsize",
        "scaleimagesto", "scaleturretsimagesto",
        "image_shadow_frames", "image_end_teamcolors",
        "beamimageoffsetrate", "beamimagestartrotated", "beamimageendrotated",
        "image_applyteamcolors", "image_drawoffsetx", "image_drawoffsety",
        "iconimageuseintext"
    )

    /**
     * 接受阴影枚举值的图片属性（AUTO / AUTO_ANIMATED / NONE）。
     * 官方属性文档：`image_shadow` 填 NONE 或 AUTO 又或者是 AUTO_ANIMATED，或图像文件；
     * `imageShadow`（效果节）、`image_end_shadow` / `image_foot_shadow`（腿节）示例均为 AUTO。
     * 贴花节的 `image_shadow` 文档只写 file（示例 XXX.png），不支持这三个值，由
     * [supportsShadowAutoValues] 按节排除。
     */
    private val shadowAutoValueNames = setOf(
        "image_shadow", "imageshadow", "image_end_shadow", "image_foot_shadow"
    )

    /** 阴影枚举值的展示顺序与缺省说明（说明优先取 value_desc.json 的 data.value.image.*） */
    private val shadowAutoValues = listOf(
        "AUTO" to "自动生成阴影",
        "AUTO_ANIMATED" to "自动生成阴影（跟随动画帧）",
        "NONE" to "无阴影"
    )

    /** 当前属性是否接受阴影枚举值 */
    private fun supportsShadowAutoValues(request: ValueCompletionRequest): Boolean {
        val prop = request.findProperty() ?: return false
        val names = listOfNotNull(prop.name, prop.name_en).map { it.trim().lowercase() }
        if (names.none { it in shadowAutoValueNames }) return false
        // 贴花节 image_shadow 只接受图片文件，不给枚举值
        if (names.any { it == "image_shadow" } && request.sectionName == "贴花") return false
        return true
    }

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        val prop = request.findProperty() ?: return false
        val names = listOfNotNull(prop.name, prop.name_en).map { it.trim().lowercase() }
        // 精确命中名单；builtFrom_{NUM}_icon 为动态键（builtFrom_1_icon 等），按前缀/后缀精确匹配
        if (names.any { it in imagePathNameSet }) return true
        if (names.any { it.startsWith("builtfrom_") && it.endsWith("_icon") }) return true
        // type 初筛兜底：type 归一化后含 image/drawable 视为图片路径，
        // 但对名单确认非路径的属性硬编码排除（type 不可信，用名单兜底修正）。
        val normType = prop.type.lowercase().replace(" ", "")
        val typeMatch = normType.contains("image") || normType.contains("drawable")
        if (typeMatch && names.none { it in imageTypeExcludeNames }) return true
        // 中文属性名经翻译库反查英文后，同样按名单精确匹配，不做 contains 模糊判定
        if (request.isChineseName()) {
            val en = request.toEnglishName().trim().lowercase()
            if (en in imagePathNameSet) return true
            if (en.startsWith("builtfrom_") && en.endsWith("_icon")) return true
            if (typeMatch && en !in imageTypeExcludeNames) return true
        }
        return false
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prop = request.findProperty()
        // 打了空格 / Tab：空格不是合法分隔符——
        // type=images（贴花列表图像，可多值，如 `carFloor.png, carBody.png*3`）→ 只弹「,」；
        // 其余单值图片路径（官方示例与真实项目用法全为单值，322 处 image 无一多值）→ 值已写完，不弹。
        if (needsCommaSeparator(request)) {
            val isMulti = prop != null && prop.type.lowercase().replace(" ", "") == "images"
            return if (isMulti) {
                listOf(commaSeparatorItem("图片分隔符（值与值之间用逗号分隔）"))
            } else {
                emptyList()
            }
        }
        // 项目根用 request 携带的当前项目根（与 ProjectImageCache 刷新路径保持一致），兜底 defaultModPath
        val projectPath = request.projectRoot.ifBlank { SettingsManager.defaultModPath() }

        val prefix = request.valuePrefix
        val lower = prefix.lowercase()
        val inShared = lower.startsWith("shared:") || lower == "shared"
        val inCore = lower.startsWith("core:") || lower == "core"
        val inRoot = lower.startsWith("root:") || lower == "root"
        val browsingBuiltin = inShared || inCore
        val rawLen = request.rawValuePrefixLength

        val items = mutableListOf<CompletionProvider.CompletionItem>()

        // 1) 项目图片：非内置浏览态时给出。
        //    默认（未输入/未点 ROOT:）只列当前编辑文件所在目录（同路径）的文件，以裸名显示；
        //    跨目录的 ROOT:/ 项仅在进入 ROOT: 浏览态（点击 ROOT: 或输入 ROOT:/）后才显示。
        if (!browsingBuiltin && projectPath.isNotBlank()) {
            if (inRoot) {
                val sub = lower.removePrefix("root:").trim()
                ProjectImageCache.query(projectPath, sub, limit = 50).forEach { rel ->
                    items.add(
                        createValueItem(
                            label = "ROOT:/$rel",
                            detail = "项目图片 · ROOT:/",
                            insertText = "ROOT:/$rel",
                            prefixLength = rawLen,
                            valueType = "string"
                        )
                    )
                }
            } else {
                // 同路径（裸名）默认显示；跨目录 ROOT:/ 在这个模式下不出现，避免默认列表被全局目录污染
                ProjectImageCache.query(projectPath, prefix, limit = 500).forEach { rel ->
                    val insert = formatProjectRefInsertValue(rel, request.currentFileDir, projectPath)
                    if (!insert.startsWith("ROOT:/")) {
                        items.add(
                            createValueItem(
                                label = insert,
                                detail = "图片路径",
                                insertText = insert,
                                prefixLength = rawLen,
                                valueType = "string"
                            )
                        )
                    }
                }
            }
        }

        // 2) SHARED:/CORE: 内置共享图 = 游戏内置 drawable ∪ 原版单位名
        if (browsingBuiltin) {
            val sub = when {
                lower.startsWith("shared:") -> prefix.drop("shared:".length)
                lower.startsWith("core:") -> prefix.drop("core:".length)
                else -> ""
            }
            val builtinImages = (SharedImagesRepository.get(request.context) +
                VanillaUnitsRepository.get(request.context))
                .filter { it.lowercase().contains(sub.lowercase()) }
                .sorted()
            builtinImages.forEach { name ->
                val useCore = lower.startsWith("core:")
                val insert = if (useCore) "CORE:$name" else "SHARED:$name"
                // 原版单位走 vanillaunit 说明；共享 drawable 走 image 说明；未命中回退「内置共享图」
                val descKey = if (name in VanillaUnitsRepository.get(request.context)) {
                    "data.value.vanillaunit.$name"
                } else {
                    "data.value.image.$name"
                }
                val perName = ValueDescriptionRepository.lookup(request.context, descKey)
                items.add(
                    createValueItem(
                        label = insert,
                        detail = perName ?: "内置共享图",
                        insertText = insert,
                        prefixLength = rawLen,
                        valueType = "string"
                    )
                )
            }
        }

        // 3) 前缀选择项：让用户「挑」而非「敲」SHARED:/CORE:/ROOT:。
        //    空值或当前输入与某前缀开头匹配时给出，选中即插入该前缀并展开对应候选。
        if (projectPath.isNotBlank() || lower.isEmpty()) {
            listOf("SHARED:", "CORE:", "ROOT:").forEach { sel ->
                if (lower.isEmpty() || sel.lowercase().startsWith(lower)) {
                    items.add(
                        createValueItem(
                            label = sel,
                            detail = when (sel) {
                                "SHARED:" -> "内置共享图像（游戏资源）"
                                "CORE:" -> "内置核心资源（游戏资源）"
                                else -> "跨目录引用项目图片"
                            },
                            insertText = sel,
                            prefixLength = rawLen,
                            valueType = "string"
                        )
                    )
                }
            }
        }

        // 默认态把 ROOT: 前缀选择项置顶，便于用户直接点选展开跨目录图片
        if (!inRoot && !browsingBuiltin) {
            items.firstOrNull { it.insertText == "ROOT:" }?.let {
                items.remove(it)
                items.add(0, it)
            }
        }

        // 4) 阴影枚举值：阴影类图片属性额外提供 AUTO / AUTO_ANIMATED / NONE。
        //    只在非 SHARED:/CORE:/ROOT: 浏览态给出，并按当前输入前缀过滤（输入 AU 也命中）。
        if (!browsingBuiltin && !inRoot && supportsShadowAutoValues(request)) {
            val enumItems = shadowAutoValues
                .filter { completionMatchLevel(prefix, it.first) > 0 }
                .map { (value, fallback) ->
                    createValueItem(
                        label = value,
                        detail = ValueDescriptionRepository.lookup(request.context, "data.value.image.$value")
                            ?: fallback,
                        insertText = value,
                        prefixLength = rawLen,
                        valueType = "string"
                    )
                }
            if (enumItems.isNotEmpty()) items.addAll(0, enumItems)
        }
        return items
    }
}
