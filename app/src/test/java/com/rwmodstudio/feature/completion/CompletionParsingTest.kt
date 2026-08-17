package com.rwmodstudio.feature.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionParsingTest {

    @Test
    fun splitKeyValueLineUsesFirstColon() {
        val (key, rawValue) = splitKeyValueLine("图像: ROOT:ui/坦克.png")
        assertEquals("图像", key, "key should be before first colon")
        assertEquals(" ROOT:ui/坦克.png", rawValue, "value prefix keeps rest including inner colon")
    }

    @Test
    fun splitKeyValueLinePlainLine() {
        val (key, rawValue) = splitKeyValueLine("tags: a")
        assertEquals("tags", key)
        assertEquals(" a", rawValue)
    }

    @Test
    fun splitKeyValueLineTrimsKey() {
        val (key, _) = splitKeyValueLine("  生命值 : 200")
        assertEquals("生命值", key)
    }

    @Test
    fun splitKeyValueLineWithoutColonReturnsEmpty() {
        val (key, rawValue) = splitKeyValueLine("no colon here")
        assertEquals("", key)
        assertEquals("", rawValue)
    }

    @Test
    fun replaceablePrefixSplitsOnFullWidthComma() {
        assertEquals("标", replaceableValuePrefix("标签1，标"), "full-width comma should be a delimiter")
    }

    @Test
    fun replaceablePrefixSplitsOnAsciiCommaAndSpace() {
        assertEquals("b", replaceableValuePrefix("a, b"))
        assertEquals("c", replaceableValuePrefix("a b c"))
    }

    @Test
    fun replaceablePrefixKeepsWholeWhenNoDelimiter() {
        assertEquals("abc", replaceableValuePrefix("abc"))
        assertEquals("", replaceableValuePrefix(""))
    }

    @Test
    fun replaceablePrefixSplitsOnParenthesis() {
        assertEquals("偏移量y", replaceableValuePrefix("防御建筑模版(偏移量x=10,偏移量y"))
    }

    // ===== 括号内只出参数早退（shouldReturnValueOnlyInParens） =====

    @Test
    fun shouldReturnValueOnlyInParensKnownFunctionAnyPrefix() {
        assertTrue(shouldReturnValueOnlyInParens("自动触发", insideParens = true, knownParamFunction = true, rawPrefixEmpty = false))
        assertTrue(shouldReturnValueOnlyInParens("自动触发", true, true, true))
    }

    @Test
    fun shouldReturnValueOnlyInParensUnknownFunctionOnlyEmptyPrefix() {
        // 未知函数：只有无前缀时才早退（保留原有行为）
        assertTrue(shouldReturnValueOnlyInParens("自动触发", true, false, true))
        assertFalse(shouldReturnValueOnlyInParens("自动触发", true, false, false))
    }

    @Test
    fun shouldReturnValueOnlyInParensRequiresKeyAndParen() {
        assertFalse(shouldReturnValueOnlyInParens("", true, true, true), "无键名不早退")
        assertFalse(shouldReturnValueOnlyInParens("自动触发", false, true, true), "不在括号内不早退")
    }

    // ===== 空值未知键兜底抑制（shouldSuppressEmptyValueFallback） =====

    @Test
    fun shouldSuppressEmptyValueFallbackOnlyWhenEmptyPrefixAndNoResults() {
        assertTrue(shouldSuppressEmptyValueFallback("未知键", rawPrefixEmpty = true, valueResultsEmpty = true))
        assertFalse(shouldSuppressEmptyValueFallback("未知键", rawPrefixEmpty = false, valueResultsEmpty = true), "有前缀仍按前缀过滤")
        assertFalse(shouldSuppressEmptyValueFallback("自动触发", true, false), "有值结果不抑制")
        assertFalse(shouldSuppressEmptyValueFallback("", true, true), "无键名不抑制")
    }

    // ===== 自动补全触发符：半角与全角逗号都应作为单词边界 =====

    @Test
    fun triggerCharsIncludeBothCommas() {
        assertTrue(',' in CompletionProvider.AUTO_COMPLETE_TRIGGER_CHARS, "半角逗号应为触发符")
        assertTrue('，' in CompletionProvider.AUTO_COMPLETE_TRIGGER_CHARS, "全角逗号应为触发符（与 replaceableValuePrefix 一致）")
    }

    // ===== 翻译库桥接校验（shouldBridgeToTranslationLibrary） =====

    @Test
    fun shouldBridgeOnlyWhenLabelInDict() {
        val dictKeys = setOf("自身资源", "self.resource", "自身资源类型", "自身资源大于")
        assertTrue(shouldBridgeToTranslationLibrary("自身资源", dictKeys), "字典存在的词才补翻译库项")
        assertFalse(shouldBridgeToTranslationLibrary("AI阶段", dictKeys), "不在字典的自定义资源名不误标翻译库")
        assertFalse(shouldBridgeToTranslationLibrary("真", dictKeys), "不在字典的词不桥接")
        assertFalse(shouldBridgeToTranslationLibrary("x", dictKeys), "单字符不桥接")
    }

    // ===== 值片段早退（shouldReturnValueOnlyForValueFragment） =====

    @Test
    fun shouldReturnValueOnlyForValueFragmentBranches() {
        // 光标紧跟触发符（rawPrefix 空）、值片段非空、值补全有结果 → 只返回值补全结果
        assertTrue(shouldReturnValueOnlyForValueFragment(rawPrefixEmpty = true, valuePrefixNotEmpty = true, valueResultsNotEmpty = true))
        // 有前缀（如 自）仍走通用 KEY/翻译库兜底
        assertFalse(shouldReturnValueOnlyForValueFragment(false, true, true))
        // 空值（如 自动触发: 空格后）但有值补全结果（真/假/if）→ 早退只返回值补全，不再刷全表
        assertTrue(shouldReturnValueOnlyForValueFragment(true, false, true))
        // 值补全无结果不早退
        assertFalse(shouldReturnValueOnlyForValueFragment(true, true, false))
    }

    // ===== 关键字后过滤（valueEndsWithLogicKeyword） =====

    @Test
    fun valueEndsWithLogicKeywordDetectsKeyword() {
        assertTrue(valueEndsWithLogicKeyword("自动触发: if "))
        assertTrue(valueEndsWithLogicKeyword("自动触发: if 自身在天上() and "))
        assertTrue(valueEndsWithLogicKeyword("需要条件: not"))
        assertTrue(valueEndsWithLogicKeyword("自动触发: if self.isFlying() or"))
        assertFalse(valueEndsWithLogicKeyword("自动触发: if 自身在天上()"), "末尾是闭合调用不是关键字")
        assertFalse(valueEndsWithLogicKeyword("自动触发: if 自身在天上() and 自"), "关键字后已有内容")
        assertFalse(valueEndsWithLogicKeyword("自动触发: 自身资源.gold"))
        assertFalse(valueEndsWithLogicKeyword("no colon"))
    }

    // ===== 运算符需要左操作数（hasCompleteValueBeforeCursor） =====

    @Test
    fun completeValueBeforeCursorEnablesOperators() {
        assertTrue(hasCompleteValueBeforeCursor("自动触发: 自身资源.AI阶段 "), "资源访问是完整数值")
        assertTrue(hasCompleteValueBeforeCursor("自动触发: 内存.伤害量 "), "内存访问是完整数值")
        assertTrue(hasCompleteValueBeforeCursor("自动触发: 自身血量 "), "数值 getter")
        assertTrue(hasCompleteValueBeforeCursor("自动触发: 自身在天上() "), "闭合函数调用")
        assertTrue(hasCompleteValueBeforeCursor("自动触发: 10 "))
        assertTrue(hasCompleteValueBeforeCursor("自动触发: x "))
    }

    @Test
    fun incompleteExpressionBeforeCursorDisablesOperators() {
        assertFalse(hasCompleteValueBeforeCursor("自动触发: "), "表达式开头无操作数")
        assertFalse(hasCompleteValueBeforeCursor("自动触发: if "), "关键字后无操作数")
        assertFalse(hasCompleteValueBeforeCursor("自动触发: 自身在天上() and "), "关键字后无操作数")
        assertFalse(hasCompleteValueBeforeCursor("自动触发: 自身血量 > "), "运算符后无操作数")
        assertFalse(hasCompleteValueBeforeCursor("当前动作目标."), "点后无操作数")
        assertFalse(hasCompleteValueBeforeCursor("自动触发: 真 "), "布尔常量不算数值操作数")
        assertFalse(hasCompleteValueBeforeCursor("no colon"))
    }

    // ===== 语法项 / 运算符过滤 =====

    private fun citem(label: String) = CompletionProvider.CompletionItem(label = label, type = CompletionProvider.CompletionType.VALUE)

    @Test
    fun filterLogicSyntaxItemsRemovesKeywordsAndOperators() {
        val items = listOf(citem("真"), citem("if"), citem("and"), citem("+"), citem("<"), citem("自身血量"))
        val filtered = filterLogicSyntaxItems(items, suppress = true)
        assertEquals(listOf(citem("自身血量")), filtered, "suppress 时移除 真/if/and/运算符")
        assertEquals(items, filterLogicSyntaxItems(items, suppress = false), "不 suppress 时原样返回")
    }

    @Test
    fun filterOperatorItemsOnlyRemovesOperators() {
        val items = listOf(citem("+"), citem("<="), citem("自身血量"), citem("and"))
        val filtered = filterOperatorItems(items, includeOperators = false)
        assertEquals(listOf(citem("自身血量"), citem("and")), filtered, "只移除运算符，保留值和关键字")
        assertEquals(items, filterOperatorItems(items, includeOperators = true), "需要运算符时原样返回")
    }

    // ===== 关键字后只留值（模拟 自动触发: if 的完整过滤链） =====

    @Test
    fun afterIfSpaceKeepsOnlyValues() {
        val items = listOf(
            citem("真"), citem("假"), citem("if"), citem("and"), citem("or"), citem("not"),
            citem("+"), citem(">"), citem("自身血量"), citem("敌人有此单位数量")
        )
        val entry = filterLogicEntryTokens(items, suppressEntryTokens = true)   // 值含 if → 入口抑制
        val syntax = filterLogicSyntaxItems(entry, suppress = true)              // 关键字后 → 语法项
        val result = filterOperatorItems(syntax, includeOperators = false)       // 无左操作数 → 运算符
        assertEquals(listOf(citem("自身血量"), citem("敌人有此单位数量")), result, "if 后只留值/函数")
    }

    // ===== 完整值后不提示表达式起始关键字（filterStartersAfterValue） =====

    @Test
    fun afterCompleteValueHidesStarters() {
        val items = listOf(
            citem("if"), citem("真"), citem("假"), citem("not"),
            citem("and"), citem("or"), citem(">"), citem("自身血量")
        )
        val filtered = filterStartersAfterValue(items, afterCompleteValue = true)
        assertEquals(listOf(citem("and"), citem("or"), citem(">"), citem("自身血量")), filtered, "完整值后只留组合/运算符/值")
        assertEquals(items, filterStartersAfterValue(items, afterCompleteValue = false), "非完整值后原样返回")
    }

}
