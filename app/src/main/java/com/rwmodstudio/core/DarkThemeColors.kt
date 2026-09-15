package com.rwmodstudio.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val highlightDelimiters: Boolean = true
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
            value = "#75D8AE",
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
            team = "#f86300",
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
         * **键集合必须与 [targetScopes] 完全一致**（加上不参与 scope 着色的 ui / plainText）：
         * 「自定义高亮」页的类型列表与「规则」页的类别列表都直接由它派生，
         * 两边从此是同一套词表（`HighlightRuleManagerTest.categoriesMatchCustomHighlightTypesExactly` 校验）。
         */
        val labels = mapOf(
            "ui" to "UI 高亮",
            "plainText" to "普通文本",
            "sectionBracket" to "节括号",
            "sectionSuffix" to "节后缀",
            "keyword" to "关键字",
            "logical" to "逻辑符",
            "operator" to "操作符",
            "comma" to "逗号",
            "value" to "值高亮",
            "property" to "属性/常量",
            "team" to "队伍/路径点",
            "movement" to "移动/层级",
            "path" to "路径前缀",
            "decoration" to "装饰符",
            "hexColor" to "颜色值",
            "number" to "数字",
            "string" to "字符串",
            "quote" to "字符串引号",
            "comment" to "注释",
            "memory" to "内存/快捷键",
            "reference" to "引用前缀/目标",
            "function" to "函数",
            "parameter" to "参数",
            "shortcutVariable" to "快捷变量",
            "type" to "类型",
            "modifier" to "修饰符",
            "bracket" to "方括号",
            "bracketRound" to "圆括号",
            "bracketCurly" to "花括号"
        )

        /**
         * 每个类别对应的 TextMate scope 集合。
         * 生成自定义主题时，会把这些 scope 的 foreground 替换为用户颜色。
         * 顺序靠前的类别优先匹配。
         */
        val targetScopes = linkedMapOf(
            // 节括号 + 节名一起：主题里 entity.name.section.ini 的配色和节括号完全一样（#FD80FF / #871095），
            // 规则页也只保留「节括号」一个类别（历史上「节高亮」与「节括号」在规则页重复出现）
            "sectionBracket" to setOf(
                "punctuation.definition.section.begin.ini",
                "punctuation.definition.section.end.ini",
                "entity.name.section.ini",
                "entity.name.section.prefix.ini"
            ),
            "sectionSuffix" to setOf("entity.name.section.suffix.ini"),
            "keyword" to setOf("keyword.key.ini"),
            "logical" to setOf("keyword.operator.logical.ini"),
            "operator" to setOf(
                "keyword.operator.comparison.ini",
                "keyword.operator.assignment.ini",
                "keyword.operator.arithmetic.ini",
                "punctuation.accessor.ini"
            ),
            "comma" to setOf("punctuation.separator.comma.ini"),
            "value" to setOf(
                "string.unquoted.value.ini",
                "punctuation.separator.ini",
                "punctuation.separator.forward-slash.ini"
            ),
            "property" to setOf(
                "support.variable.property.ini",
                "support.constant.sound.ini",
                "support.constant.effect.ini",
                "support.constant.spawn.ini"
            ),
            "team" to setOf(
                "constant.language.team.ini",
                "constant.language.waypoint.ini",
                "constant.language.event.ini",
                // 布尔常量与队伍值在主题里同色（历史沿革），并入同一配色键 → 规则页也只显示一个类别
                "constant.language.boolean.ini"
            ),
            "movement" to setOf(
                "constant.language.movement.ini",
                "constant.language.uppercase.ini",
                "constant.language.layer.ini",
                "constant.language.animation.ini"
            ),
            "path" to setOf("constant.language.path.ini"),
            // 装饰符：语法里「相邻引号 / 连续单引号」这类无 scoped 结构标记（自定义高亮规则的「装饰符」类别写这里）
            "decoration" to setOf("punctuation.ignored.ini"),
            "hexColor" to setOf("constant.other.color.ini"),
            "number" to setOf("constant.numeric.ini"),
            "string" to setOf(
                "string.quoted.single.ini",
                "string.quoted.double.ini",
                "string.multiline.ini"
            ),
            "quote" to setOf(
                "punctuation.definition.string.begin.ini",
                "punctuation.definition.string.end.ini"
            ),
            "memory" to setOf(
                "keyword.control.shortcut.ini",
                "keyword.control.shortcut.key.ini",
                "keyword.control.memory.ini"
            ),
            "reference" to setOf(
                "variable.other.reference.ini",
                "variable.other.reference.target.ini"
            ),
            "function" to setOf("support.function.ini"),
            "parameter" to setOf("variable.parameter.ini"),
            "shortcutVariable" to setOf(
                "variable.other.shortcut.ini",
                "punctuation.separator.shortcut.ini"
            ),
            "bracket" to setOf("punctuation.bracket.square.ini"),
            "bracketRound" to setOf("punctuation.bracket.round.ini"),
            "bracketCurly" to setOf("punctuation.bracket.curly.ini"),
            "comment" to setOf(
                "comment.line.ini",
                "comment.block.ini",
                "punctuation.definition.comment.ini"
            ),
            // ── 以下两个键是 2026-09-15 补的：主题里这两个 scope 一直有独立配色，
            //    但 targetScopes/规则页都没有对应键 → 自定义高亮里调不了、规则页写了也不生效。
            //    追加在最后，**不改动上面任何键的相对顺序**（顺序即首命中优先级）。
            "type" to setOf("storage.type.ini", "storage.type.shortcut.ini"),
            "modifier" to setOf("storage.modifier.ini")
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
