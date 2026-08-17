package com.rwmodstudio.ui.screens

import java.io.File

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomCompletionsFormatTest {

    @Test
    fun freeInputPropertyTypeMatchesNumericAndTextTypes() {
        // 数值类
        assertTrue(isFreeInputPropertyType("int"), "int should be free input")
        assertTrue(isFreeInputPropertyType("float"), "float should be free input")
        assertTrue(isFreeInputPropertyType("integer"), "integer should be free input")
        assertTrue(isFreeInputPropertyType("ints"), "ints should be free input")
        assertTrue(isFreeInputPropertyType("number"), "number should be free input")
        assertTrue(isFreeInputPropertyType("logicNumber"), "logicNumber should be free input")
        assertTrue(isFreeInputPropertyType("time"), "time should be free input")
        assertTrue(isFreeInputPropertyType("time (seconds)"), "time (seconds) should be free input")
        assertTrue(isFreeInputPropertyType("addEnergy"), "addEnergy should be free input")
        // 文本类
        assertTrue(isFreeInputPropertyType("string"), "string should be free input")
        assertTrue(isFreeInputPropertyType("String"), "String should be free input")
        assertTrue(isFreeInputPropertyType("LocaleString"), "LocaleString should be free input")
        assertTrue(isFreeInputPropertyType("string(s)"), "string(s) should be free input")
        assertTrue(isFreeInputPropertyType("strings(s)"), "strings(s) should be free input")
        // 大小写与首尾空白归一化
        assertTrue(isFreeInputPropertyType("  STRING "), "whitespace/case should be normalized")
    }

    @Test
    fun freeInputPropertyTypeRejectsNonFreeInputTypes() {
        assertFalse(isFreeInputPropertyType("bool"), "bool should not be free input")
        assertFalse(isFreeInputPropertyType("Boolean"), "Boolean should not be free input")
        assertFalse(isFreeInputPropertyType("LogicBoolean"), "LogicBoolean should not be free input")
        assertFalse(isFreeInputPropertyType("price"), "price resource pair should not be free input")
        assertFalse(isFreeInputPropertyType("price(s)"), "price(s) should not be free input")
        assertFalse(isFreeInputPropertyType("customPrice"), "customPrice should not be free input")
        assertFalse(isFreeInputPropertyType("logic"), "logic should not be free input")
        assertFalse(isFreeInputPropertyType("float / bool"), "composite type should not be free input")
        assertFalse(isFreeInputPropertyType("int / price"), "composite type should not be free input")
        assertFalse(isFreeInputPropertyType("bool/int"), "composite type should not be free input")
        assertFalse(isFreeInputPropertyType("bool/string"), "composite type should not be free input")
        assertFalse(isFreeInputPropertyType("int/string"), "composite type should not be free input")
        assertFalse(isFreeInputPropertyType("point"), "point should not be free input")
        assertFalse(isFreeInputPropertyType(""), "empty type should not be free input")
    }

    @Test
    fun numericFormatInsertsNameColonOnly() {
        val item = CustomCompletion(
            name = "生命值",
            value = "200",
            detail = "int",
            category = listOf("核心"),
            formatCategory = FORMAT_EMPTY_VALUE,
            nameEn = "maxHp"
        )
        val providerItem = customCompletionsToProviderItems(listOf(item)).single()
        assertEquals("生命值:", providerItem.insertText, "numeric property should insert name: only")
    }

    @Test
    fun textFormatInsertsNameColonOnly() {
        val item = CustomCompletion(
            name = "描述",
            value = "示例描述",
            detail = "string",
            category = listOf("核心"),
            formatCategory = FORMAT_EMPTY_VALUE,
            nameEn = "description"
        )
        val providerItem = customCompletionsToProviderItems(listOf(item)).single()
        assertEquals("描述:", providerItem.insertText, "text property should insert name: only")
    }

    @Test
    fun numericFormatIgnoresValueEvenWhenEmpty() {
        val item = CustomCompletion(
            name = "初始能量",
            value = "",
            category = listOf("核心"),
            formatCategory = FORMAT_EMPTY_VALUE,
            nameEn = "startEnergy"
        )
        val providerItem = customCompletionsToProviderItems(listOf(item)).single()
        assertEquals("初始能量:", providerItem.insertText)
    }

    @Test
    fun propertyFormatStillInsertsNameValue() {
        val item = CustomCompletion(
            name = "颜色",
            value = "#ff0000",
            category = listOf("核心"),
            formatCategory = "属性",
            nameEn = "color"
        )
        val providerItem = customCompletionsToProviderItems(listOf(item)).single()
        assertEquals("颜色:#ff0000", providerItem.insertText, "plain property should keep name:value")
    }

    @Test
    fun propertyFormatWithEmptyValueInsertsNameColon() {
        val item = CustomCompletion(
            name = "名称",
            value = "",
            category = listOf("核心"),
            formatCategory = "属性",
            nameEn = "name"
        )
        val providerItem = customCompletionsToProviderItems(listOf(item)).single()
        assertEquals("名称:", providerItem.insertText)
    }

    // ===== 空值属性更名兼容 =====

    @Test
    fun emptyValueFormatInsertsNameColonOnly() {
        val item = CustomCompletion(
            name = "自动触发", value = "真", category = listOf("行动"),
            formatCategory = FORMAT_EMPTY_VALUE, nameEn = "autoTrigger"
        )
        val providerItem = customCompletionsToProviderItems(listOf(item)).single()
        assertEquals("自动触发:", providerItem.insertText, "空值属性只插 名称:")
    }

    @Test
    fun legacyNumericFormatStillInsertsNameColon() {
        // 旧表里已存的「数值属性」项，插入仍按「名称:」
        val item = CustomCompletion(
            name = "生命值", value = "200", category = listOf("核心"),
            formatCategory = LEGACY_FORMAT_NUMERIC, nameEn = "maxHp"
        )
        val providerItem = customCompletionsToProviderItems(listOf(item)).single()
        assertEquals("生命值:", providerItem.insertText)
    }

    @Test
    fun parseCustomCompletionsNormalizesLegacyNumericFormat() {
        val parsed = parseCustomCompletions(
            """[{"name":"生命值","value":"200","category":["核心"],"formatCategory":"数值属性","nameEn":"maxHp"}]"""
        )
        assertEquals(1, parsed.size)
        assertEquals(FORMAT_EMPTY_VALUE, parsed.single().formatCategory, "旧「数值属性」应归一化为「空值属性」")
    }

    // ===== values 默认值五类规则 =====

    @Test
    fun valuesDefaultNamedParamFunction() {
        // A 命名参数函数 → 非生成参数，裸名
        assertEquals("自我恢复", valuesDefaultValue("自我恢复", false))
        assertEquals("self.numberOfQueuedWaypoints()", valuesDefaultValue("self.numberOfQueuedWaypoints()", false))
        assertEquals("queueItemAdded(withActionTag=)", valuesDefaultValue("queueItemAdded(withActionTag=)", false))
    }

    @Test
    fun valuesDefaultNoParamFunctionBareName() {
        // B 无参函数 → 裸名
        assertEquals("自身在天上", valuesDefaultValue("自身在天上", false))
        assertEquals("self.isReversing()", valuesDefaultValue("self.isReversing()", false))
    }

    @Test
    fun valuesDefaultPositionalFunction() {
        // C 位置参数函数 → 名称(
        assertEquals("rnd(min, max)", valuesDefaultValue("rnd(min, max)", false))
        assertEquals("distance(x1, y1, x2, y2)", valuesDefaultValue("distance(x1, y1, x2, y2)", false))
    }

    @Test
    fun valuesDefaultSpawnParam() {
        // D 生成参数 → 名称=
        assertEquals("偏移量x=", valuesDefaultValue("偏移量x", true))
        assertEquals("产生机会=", valuesDefaultValue("产生机会", true))
    }

    @Test
    fun valuesDefaultBareForOthers() {
        // E/F 其余 → 裸名
        assertEquals("真", valuesDefaultValue("真", false))
        assertEquals("if", valuesDefaultValue("if", false))
        assertEquals("创建", valuesDefaultValue("创建", false))
        assertEquals("资金", valuesDefaultValue("资金", false))
        assertEquals("父单位", valuesDefaultValue("父单位", false))
    }

    // ===== 附件表同步更新 =====

    @Test
    fun extraCompletionsAllUseEmptyValueFormat() {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/extra_completions.json"),
            File(System.getProperty("user.dir"), "src/main/assets/data/extra_completions.json"),
            File("app/src/main/assets/data/extra_completions.json"),
            File("src/main/assets/data/extra_completions.json")
        )
        val file = candidates.firstOrNull { it.exists() } ?: return
        val items = parseCustomCompletions(file.readText())
        assertTrue(items.isNotEmpty(), "附件表不应为空")
        items.forEach { item ->
            assertEquals(FORMAT_EMPTY_VALUE, item.formatCategory, "附件表条目应全部为 空值属性: ${item.name}")
            assertTrue(item.value.isBlank(), "附件表条目 value 应清空: ${item.name}")
        }
    }
}