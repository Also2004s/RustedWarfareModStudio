package com.rwmodstudio.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 用户新增的高亮类型（「规则」页里「新建类型」建出来的）。
 *
 * 与内置类型的区别：内置类型的 scope 集合写死在 [DarkThemeColors.targetScopes] 里，
 * 这里由用户指定一个 scope（**留空则自动生成 `user.custom.<id>`**）。
 * 配合「规则」页给这个类型写关键字/正则规则（规则会带上这个 scope），就能高亮任意自定义写法：
 * 用户规则插进语法最前 → 产生这个 scope → 主题里按 [color] 上色（[DarkThemeColors] 的固定字段装不下可变数量，
 * 所以颜色独立存在这里）。
 *
 * @param id 稳定唯一标识（`custom_<时间戳>`），与内置配色键不会撞名
 * @param label 界面显示名
 * @param scope TextMate scope；留空 = 自动生成
 * @param color 该类型的颜色（大写 `#RRGGBB`）
 */
@Serializable
data class CustomHighlightType(
    val id: String,
    val label: String,
    val scope: String = "",
    val color: String = "#FFFFFF"
) {
    /** 实际使用的 scope：用户没填就按 id 生成一个专属 scope */
    val effectiveScope: String get() = scope.ifBlank { "user.custom.$id" }
}

/**
 * 自定义代码高亮颜色配置。
 * 每个字段对应一组 TextMate scope，通过 scope 精确替换生成自定义主题。
 * 字段设计与 VS Code 扩展 ini-cn-highlight 的配色键一一对应。
 *
 * [Default]（内置「深色」预设）的字段默认值来源：2026-09-15 按用户手机端「自定义高亮」
 * （settings.json 的 dark_token_colors）原样复刻，用于替代原先的深色高亮；手机端未改动过的分类
 * （ui / boolean / constant / team / hexColor / function / bracket / bracketCurly）保持原默认值。
 * 修改此处等于同时改「深色」预设与「自定义」主题的初始配色。
 */
@Serializable
 data class DarkThemeColors(
    val ui: String = "#569CD6",
    val plainText: String = "#FFFFFF",

    val section: String = "#FD80FF",
    val sectionSuffix: String = "#FF5551",
    val sectionBracket: String = "#FD80FF",

    val keyword: String = "#84FF76",
    val control: String = "#FFADF9",
    val other: String = "#FFCDDC",

    val value: String = "#FFE4BB",
    val property: String = "#FFCDDB",

    val comment: String = "#5D656E",
    val number: String = "#7CC7FF",

    val string: String = "#FFEF7A",
    val quote: String = "#FFEF7A",

    // team/waypoint 原先由 boolean 槽代为上色（tmLanguage 未打专用 scope），故默认值与 boolean 保持一致；
    // 现在 tmLanguage 已补 constant.language.team/waypoint.ini 专用 scope，此值即实际生效，外观与历史一致。
    val boolean: String = "#FFC83F",
    val constant: String = "#FFC83F",
    val team: String = "#FFC83F",
    val movement: String = "#BFE5FF",
    val path: String = "#BFE5FF",
    val hexColor: String = "#FDA6C0",

    val logical: String = "#FFF236",
    val operator: String = "#FFC815",
    val comma: String = "#DCFFF3",

    val memory: String = "#FFDE95",
    val reference: String = "#FFDE95",

    // function 原先由 boolean 槽代为上色，默认值保持 boolean 色以维持外观；
    // 现在 tmLanguage 已补 support.function.ini 专用 scope，此值即实际生效。
    val function: String = "#FFC83F",
    val parameter: String = "#238E7C",

    val shortcutVariable: String = "#BBE4FF",
    val declaration: String = "#BBE4FF",

    val type: String = "#FFCDDC",
    val modifier: String = "#66BAFF",

    val propertyValue: String = "#63FFDF",

    val bracket: String = "#F1D7FF",
    val bracketRound: String = "#F1D5FF",
    val bracketCurly: String = "#F1D7FF",

    val variable: String = "#9DDDFF",

    /** 是否激活「点击符号（() [] 等）匹配高亮」：勾选后点击符号用 [ui]（UI 高亮）色高亮，与深色高亮一致。默认开启（深色预设默认勾选）。 */
    val highlightDelimiters: Boolean = true,

    /**
     * 用户新增的高亮类型（见 [CustomHighlightType]），追加在内置类型之后。
     * 默认 `emptyList()` ⇒ 旧的 `settings.json` / 旧预设反序列化后就是空表，行为与以前完全一致。
     */
    val customTypes: List<CustomHighlightType> = emptyList()
) {
    companion object {
        val Default = DarkThemeColors()

        /**
         * 浅色预设：与 VS Code 扩展 ini-cn-highlight 的 light 主题对齐。
         */
        val PresetLight = DarkThemeColors(
            ui = "#1750EB",
            plainText = "#000000",
            section = "#871095",
            sectionSuffix = "#F50000",
            sectionBracket = "#871095",
            keyword = "#067D17",
            control = "#AF00DB",
            other = "#7798f3",
            value = "#60B18E",
            property = "#75D8AE",
            comment = "#6A737D",
            number = "#1750EB",
            string = "#359543",
            quote = "#359543",
            boolean = "#A92A2A",
            constant = "#A92A2A",
            team = "#A92A2A",
            movement = "#7798f3",
            path = "#7798f3",
            hexColor = "#FB7B7f",
            logical = "#deae12",
            operator = "#f2bd13",
            comma = "#99B7AD",
            memory = "#A92A2A",
            reference = "#58908A",
            function = "#A92A2A",
            parameter = "#ae8abe",
            shortcutVariable = "#7ac3fa",
            declaration = "#7ac3fa",
            type = "#7798f3",
            modifier = "#0000FF",
            propertyValue = "#267f99",
            bracket = "#deae12",
            bracketRound = "#deae12",
            bracketCurly = "#99B7AD",
            variable = "#001080",
            highlightDelimiters = false
        )

        /**
         * 纯净预设：与 VS Code 扩展 ini-cn-highlight 的 neon 主题对齐。
         */
        val PresetPure = DarkThemeColors(
            ui = "#0084ff",
            plainText = "#000000",
            section = "#0084ff",
            sectionSuffix = "#000000",
            sectionBracket = "#252424",
            keyword = "#000000",
            control = "#dd55ff",
            other = "#000000",
            value = "#000000",
            property = "#66DD99",
            comment = "#849188",
            number = "#66a3ff",
            string = "#00a055",
            quote = "#99EEBB",
            boolean = "#1c52b8",
            constant = "#1196e4",
            team = "#642800",
            movement = "#88DDDD",
            path = "#f00404",
            hexColor = "#da1212",
            logical = "#e94545",
            operator = "#11b483",
            comma = "#ec850e",
            memory = "#aab9e9",
            reference = "#26bebe",
            function = "#1c52b8",
            parameter = "#252b2b",
            shortcutVariable = "#3d6666",
            declaration = "#50a8a8",
            type = "#2d7777",
            modifier = "#66CCFF",
            propertyValue = "#99EEBB",
            bracket = "#FF8855",
            bracketRound = "#66DD99",
            bracketCurly = "#2492db",
            variable = "#aab9e9",
            highlightDelimiters = false
        )

        /**
         * 配色键 → 界面显示名。
         *
         * **键集合必须与 [targetScopes] 完全一致**（加上不参与 scope 着色的 ui / plainText），
         * **键序也必须一致**：它就是界面顺序（`ui` / `plainText` 置顶，其余按真实项目实测的
         * 出现次数降序；节族 → 数字 → 符号组 → 注释 固定收尾）。改顺序时这张表和 [targetScopes] 一起改。
         *
         * 「自定义高亮」页的类型列表与「规则」页的类别列表都直接由它派生，
         * 两边从此是同一套词表（`HighlightRuleManagerTest.categoriesMatchCustomHighlightTypesExactly` 校验）。
         *
         * 显示名以**用户看得懂**为准，不追求与 scope 名一一对应（2026-09-18 统一过一轮）：
         * `keyword` = 属性（其实是每行开头的键名）、`value` = 属性值、`team` = 枚举值
         * （它覆盖布尔 / 帧率 / 队伍 / 路径点动作 / 内置属性名这一串内置固定取值，所以不叫「队伍/路径点」）、
         * `sectionBracket` = 节前缀（节名与 `[` `]` 同色、同属一个类别）、
         * `type` = 内存类型（真实项目里只出现在 `@memory 变量:float[]` 的类型位）、
         * `memory` = 内存/变量（`@memory` / `@global` / `@define` 声明）、`shortcutVariable` = 变量名、
         * `movement` = 移动类型（`AIR/LAND/水面/两栖…`；原「移动/层级」里的「层级」scope 语法里根本不存在）。
         */
        val labels = mapOf(
            "ui" to "UI 高亮",
            "plainText" to "普通文本",
            // ── 按出现次数降序（实测：真实项目 27,501 行 / 148,787 个 token）──
            "value" to "属性值",
            "keyword" to "属性",
            "parameter" to "参数",
            "team" to "枚举值",
            "string" to "字符串",
            "reference" to "单位标记",
            "function" to "函数",
            "path" to "路径前缀",
            "hexColor" to "颜色值",
            "movement" to "移动类型",
            "shortcutVariable" to "变量名",
            "memory" to "内存/变量",
            "type" to "内存类型",
            // ── 节族（固定在数字 / 符号 / 注释之前）──
            "sectionBracket" to "节前缀",
            "sectionSuffix" to "节后缀",
            // ── 数字 ──
            "number" to "数字",
            // ── 符号（统一成一组，组内不按出现次数）──
            "comma" to "逗号",
            "bracket" to "方括号",
            "bracketRound" to "圆括号",
            "bracketCurly" to "花括号",
            "operator" to "操作符",
            "logical" to "逻辑符",
            // ── 注释（固定最末）──
            "comment" to "注释"
        )

        /**
         * **不进调色盘、但规则页仍保留**的类型。
         *
         * 历史上这里排除过 4 个类型（decoration / property / quote / modifier），2026-09-18 全部处理完毕：
         * - `decoration`（连续引号）：**并入普通文本** —— 它是语法标记 `"""` / `'''` 多行引号边界的
         *   「不上色」结构（scope 名就是 *ignored*），颜色永远跟随正文（见
         *   [com.rwmodstudio.editor.SoraEditorInitializer.applyScopeColors] 的接管逻辑），不再单独立类型；
         * - `property` / `quote` / `modifier`：scope 在语法里一条规则都没有，整类空转，已从类型表整体移除。
         *
         * ⇒ 现在为空集。入口保留：将来若再出现「有配色字段但没有 scope 规则」的类型，从这里排除。
         */
        private val nonColorableCategories: Set<String> = emptySet()

        /**
         * 「自定义高亮」里**可调色**的类型 = [labels] 的键序去掉 [nonColorableCategories]。
         *
         * 把"调了没反应"的类型摆进调色盘只会让用户以为改坏了；规则页不受影响。
         */
        val colorableCategories: List<String> = labels.keys.filterNot { it in nonColorableCategories }

        // ── 内置 + 用户新增：类型表的「运行时视图」 ──
        // 内置类型写在常量表里（编译期固定），用户新增类型存在 DarkThemeColors.customTypes 里（可变），
        // 所以凡是"要显示/遍历全部类型"的地方都得走下面这几个函数，不能只看 labels / targetScopes。

        /** 全部可调色类型：内置在前，用户新增追加在后（顺序即界面顺序） */
        fun colorableCategoriesOf(colors: DarkThemeColors): List<String> =
            colorableCategories + colors.customTypes.map { it.id }

        /** 显示名：内置 → 用户新增 → 回落 id */
        fun labelOf(id: String, colors: DarkThemeColors): String =
            labels[id] ?: colors.customTypes.firstOrNull { it.id == id }?.label ?: id

        /** 该类型覆盖的 scope：内置取 [targetScopes]，用户新增取它自己的 scope */
        fun scopesOf(id: String, colors: DarkThemeColors): Set<String> =
            targetScopes[id] ?: colors.customTypes.firstOrNull { it.id == id }
                ?.let { setOf(it.effectiveScope) }.orEmpty()

        /** 用户新增类型的颜色；内置类型返回 null（它们的颜色在各自的固定字段里） */
        fun customColorOf(id: String, colors: DarkThemeColors): String? =
            colors.customTypes.firstOrNull { it.id == id }?.color

        /** 新建用户类型时生成 id（`custom_<时间戳>`，撞了就加后缀） */
        fun newCustomTypeId(existing: List<CustomHighlightType>): String {
            val used = existing.map { it.id }.toSet()
            var id = "custom_" + System.currentTimeMillis()
            var n = 0
            while (id in used) id = "custom_" + System.currentTimeMillis() + "_" + (++n)
            return id
        }

        /**
         * 每个类别对应的 TextMate scope 集合。
         * 生成自定义主题时，会把这些 scope 的 foreground 替换为用户颜色。
         *
         * **键序 = 界面顺序**（与 [labels] 一致）：`ui` / `plainText` 不在本表里、固定置顶，
         * 其余按**真实项目实测的出现次数降序**（`人机的玩笑`：27,501 行 / 148,898 个 token），
         * 节族 → 数字 → 符号组 → 注释 固定收尾。
         *
         * 顺序同时决定「同一 scope 命中多个类别时」的首命中优先级 ——
         * 所以**同一个 scope 不允许出现在两个类别里**（`HighlightRuleManagerTest` 有校验）。
         * 2026-09-18 重排前已核实：29 个类别零重复 scope、跨类前缀不重叠 ⇒ 重排不改变任何 token 的配色归属。
         */
        val targetScopes = linkedMapOf(
            // ── 按出现次数降序 ──
            "value" to setOf(
                "string.unquoted.value.ini",
                "punctuation.separator.ini",
                "punctuation.separator.forward-slash.ini"
            ),
            "keyword" to setOf("keyword.key.ini"),
            "parameter" to setOf("variable.parameter.ini"),
            "team" to setOf(
                "constant.language.team.ini",
                "constant.language.waypoint.ini",
                "constant.language.event.ini",
                // 布尔常量与队伍值在主题里同色（历史沿革），并入同一配色键 → 规则页也只显示一个类别
                "constant.language.boolean.ini"
            ),
            "string" to setOf(
                "string.quoted.single.ini",
                "string.quoted.double.ini",
                "string.multiline.ini"
            ),
            "reference" to setOf(
                "variable.other.reference.ini",
                "variable.other.reference.target.ini"
            ),
            "function" to setOf("support.function.ini"),
            "path" to setOf("constant.language.path.ini"),
            "hexColor" to setOf("constant.other.color.ini"),
            "movement" to setOf(
                "constant.language.movement.ini",
                "constant.language.uppercase.ini",
                "constant.language.layer.ini",
                "constant.language.animation.ini"
            ),
            "shortcutVariable" to setOf(
                "variable.other.shortcut.ini",
                "punctuation.separator.shortcut.ini"
            ),
            "memory" to setOf(
                "keyword.control.shortcut.ini",
                "keyword.control.shortcut.key.ini",
                "keyword.control.memory.ini"
            ),
            // type 是 2026-09-15 补的：主题里这个 scope 一直有独立配色，
            // 但那之前 targetScopes/规则页都没有对应键 → 自定义高亮里调不了、规则页写了也不生效。
            "type" to setOf("storage.type.ini", "storage.type.shortcut.ini"),
            // ── 节族：固定在数字 / 符号 / 注释之前 ──
            // 节前缀 + 节名一起：主题里 entity.name.section.ini 的配色和节括号完全一样（#FD80FF / #871095），
            // 规则页也只保留「节前缀」一个类别（历史上「节高亮」与「节括号」在规则页重复出现）
            "sectionBracket" to setOf(
                "punctuation.definition.section.begin.ini",
                "punctuation.definition.section.end.ini",
                "entity.name.section.ini",
                "entity.name.section.prefix.ini"
            ),
            "sectionSuffix" to setOf("entity.name.section.suffix.ini"),
            // ── 数字 ──
            "number" to setOf("constant.numeric.ini"),
            // ── 符号：统一成一组（组内不按出现次数）──
            "comma" to setOf("punctuation.separator.comma.ini"),
            "bracket" to setOf("punctuation.bracket.square.ini"),
            "bracketRound" to setOf("punctuation.bracket.round.ini"),
            "bracketCurly" to setOf("punctuation.bracket.curly.ini"),
            "operator" to setOf(
                "keyword.operator.comparison.ini",
                "keyword.operator.assignment.ini",
                "keyword.operator.arithmetic.ini",
                "punctuation.accessor.ini"
            ),
            "logical" to setOf("keyword.operator.logical.ini"),
            // ── 注释：固定最末 ──
            "comment" to setOf(
                "comment.line.ini",
                "comment.block.ini",
                "punctuation.definition.comment.ini"
            )
        )
    }
}

private val json = Json { ignoreUnknownKeys = true }

fun DarkThemeColors.toJson(): String = json.encodeToString(this)

fun String.toDarkThemeColors(): DarkThemeColors = try {
    json.decodeFromString<DarkThemeColors>(this)
} catch (_: Exception) {
    DarkThemeColors.Default
}
