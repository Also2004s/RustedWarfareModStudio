package com.rwmodstudio.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 自定义代码高亮颜色配置。
 * 每个字段对应一组 TextMate scope，通过 scope 精确替换生成自定义主题。
 * 字段设计与 VS Code 扩展 ini-cn-highlight 的配色键一一对应。
 */
@Serializable
 data class DarkThemeColors(
    val ui: String = "#569CD6",
    val plainText: String = "#D4D4D4",

    val section: String = "#fd80ff",
    val sectionSuffix: String = "#ff5551",
    val sectionBracket: String = "#871095",

    val keyword: String = "#6cd161",
    val control: String = "#C586C0",
    val other: String = "#b4919b",

    val value: String = "#b7939d",
    val property: String = "#b7939d",

    val comment: String = "#6A737D",
    val number: String = "#7ac3fa",

    val string: String = "#e5d76e",
    val quote: String = "#e5d76e",

    val boolean: String = "#FFC83F",
    val constant: String = "#FFC83F",
    val team: String = "#FFC83F",
    val movement: String = "#B9DEF7",
    val path: String = "#B9DEF7",
    val hexColor: String = "#FDA6C0",

    val logical: String = "#fff236",
    val operator: String = "#deae12",
    val comma: String = "#CDEEE3",

    val memory: String = "#f2d38d",
    val reference: String = "#f2d38d",

    val function: String = "#DCDCAA",
    val parameter: String = "#58908a",

    val shortcutVariable: String = "#99bbd1",
    val declaration: String = "#99bbd1",

    val type: String = "#b4919b",
    val modifier: String = "#569CD6",

    val propertyValue: String = "#4EC9B0",

    val bracket: String = "#F1D7FF",
    val bracketRound: String = "#AD99B7",
    val bracketCurly: String = "#F1D7FF",

    val variable: String = "#9CDCFE"
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
            function = "#795E26",
            parameter = "#ae8abe",
            shortcutVariable = "#7ac3fa",
            declaration = "#7ac3fa",
            type = "#7798f3",
            modifier = "#0000FF",
            propertyValue = "#267f99",
            bracket = "#deae12",
            bracketRound = "#deae12",
            bracketCurly = "#99B7AD",
            variable = "#001080"
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
            function = "#0e4e4e",
            parameter = "#252b2b",
            shortcutVariable = "#3d6666",
            declaration = "#50a8a8",
            type = "#2d7777",
            modifier = "#66CCFF",
            propertyValue = "#99EEBB",
            bracket = "#FF8855",
            bracketRound = "#66DD99",
            bracketCurly = "#2492db",
            variable = "#aab9e9"
        )

        val labels = mapOf(
            "ui" to "UI 高亮",
            "plainText" to "普通文本",
            "section" to "节高亮",
            "sectionSuffix" to "节后缀",
            "sectionBracket" to "节括号",
            "keyword" to "关键字",
            "control" to "控制关键字",
            "other" to "其它关键字",
            "value" to "值高亮",
            "property" to "属性/常量",
            "comment" to "注释",
            "number" to "数字",
            "string" to "字符串",
            "quote" to "字符串引号",
            "boolean" to "布尔常量",
            "constant" to "通用常量",
            "team" to "队伍/路径点",
            "movement" to "移动/层级",
            "path" to "路径前缀",
            "hexColor" to "颜色值",
            "logical" to "逻辑符",
            "operator" to "操作符",
            "comma" to "逗号",
            "memory" to "内存/快捷键",
            "reference" to "引用前缀/目标",
            "function" to "函数",
            "parameter" to "参数",
            "shortcutVariable" to "快捷变量",
            "declaration" to "变量声明",
            "type" to "类型",
            "modifier" to "修饰符",
            "propertyValue" to "属性值",
            "bracket" to "方括号",
            "bracketRound" to "圆括号",
            "bracketCurly" to "花括号",
            "variable" to "变量"
        )

        /**
         * 每个类别对应的 TextMate scope 集合。
         * 生成自定义主题时，会把这些 scope 的 foreground 替换为用户颜色。
         * 顺序靠前的类别优先匹配。
         */
        val targetScopes = linkedMapOf(
            "sectionBracket" to setOf(
                "punctuation.definition.section.begin.ini",
                "punctuation.definition.section.end.ini"
            ),
            "sectionSuffix" to setOf("entity.name.section.suffix.ini"),
            "section" to setOf(
                "entity.name.section.ini",
                "entity.name.section.prefix.ini"
            ),
            "keyword" to setOf("keyword.key.ini"),
            "control" to setOf("keyword.control.ini"),
            "other" to setOf("keyword.other.ini"),
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
            "boolean" to setOf("constant.language.boolean.ini"),
            "constant" to setOf("constant.language.ini"),
            "team" to setOf(
                "constant.language.team.ini",
                "constant.language.waypoint.ini",
                "constant.language.event.ini"
            ),
            "movement" to setOf(
                "constant.language.movement.ini",
                "constant.language.uppercase.ini",
                "constant.language.layer.ini",
                "constant.language.animation.ini"
            ),
            "path" to setOf("constant.language.path.ini"),
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
            "propertyValue" to setOf(
                "meta.interpolation.expression.ini",
                "support.type.property-value.ini"
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
            "declaration" to setOf("variable.other.declaration.ini"),
            "type" to setOf(
                "storage.type.ini",
                "storage.type.shortcut.ini"
            ),
            "modifier" to setOf("storage.modifier.ini"),
            "bracket" to setOf("punctuation.bracket.square.ini"),
            "bracketRound" to setOf("punctuation.bracket.round.ini"),
            "bracketCurly" to setOf("punctuation.bracket.curly.ini"),
            "variable" to setOf("variable.other.ini"),
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
