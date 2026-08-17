package com.rwmodstudio.feature.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValueDedupTest {

    private fun item(label: String, type: CompletionProvider.CompletionType, user: Boolean = false) =
        CompletionProvider.CompletionItem(
            label = label,
            type = type,
            isUserCompletion = user
        )

    @Test
    fun keyBeatsValueOnSameLabel() {
        val value = item("and", CompletionProvider.CompletionType.VALUE)
        val key = item("and", CompletionProvider.CompletionType.KEY)
        val deduped = dedupeValueByKeyPriority(listOf(value), listOf(value, key))
        assertEquals(listOf(key), deduped, "同 label 时保留 KEY 丢弃 VALUE")
    }

    @Test
    fun differentLabelsKeepBoth() {
        val value = item("敌人有此单位数量", CompletionProvider.CompletionType.VALUE)
        val key = item("and", CompletionProvider.CompletionType.KEY)
        val all = listOf(value, key)
        val deduped = dedupeValueByKeyPriority(listOf(value), all)
        assertEquals(all, deduped, "无同 label 碰撞时原样保留")
    }

    @Test
    fun emptyValueResultsReturnsAllUnchanged() {
        val key = item("and", CompletionProvider.CompletionType.KEY)
        assertEquals(listOf(key), dedupeValueByKeyPriority(emptyList(), listOf(key)))
    }

    @Test
    fun userCompletionKeyAlsoBeatsValue() {
        val value = item("敌人有此单位数量", CompletionProvider.CompletionType.VALUE)
        val userKey = item("敌人有此单位数量", CompletionProvider.CompletionType.KEY, user = true)
        val deduped = dedupeValueByKeyPriority(listOf(value), listOf(value, userKey))
        assertEquals(listOf(userKey), deduped, "用户表 KEY 同样优先于 VALUE")
        assertTrue(deduped.single().isUserCompletion)
    }

    @Test
    fun valueOnlyListUnchanged() {
        val value = item("fish", CompletionProvider.CompletionType.VALUE)
        assertEquals(listOf(value), dedupeValueByKeyPriority(listOf(value), listOf(value)))
    }

    // ===== 排序恢复：与值补全同名项按值补全原始顺序置顶 =====

    @Test
    fun collidingKeysSortByValueOrder() {
        val key = { label: String, cat: List<String> ->
            item(label, CompletionProvider.CompletionType.KEY).copy(category = cat)
        }
        val items = listOf(
            key("and", listOf("values")),
            key("if", listOf("values")),
            key("真", listOf("values")),
            key("假", listOf("values")),
            key("name", listOf("核心"))
        )
        val ordered = orderByValueCollision(items, listOf("真", "假", "if", "and", "or"))
        assertEquals(listOf("真", "假", "if", "and", "name"), ordered.map { it.label }, "同名项按值顺序置顶，普通键在后")
    }

    @Test
    fun valueTypeItemSortsBeforeCollidingKey() {
        val value = item("真", CompletionProvider.CompletionType.VALUE)
        val key = item("真", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        val plain = item("name", CompletionProvider.CompletionType.KEY)
        val ordered = orderByValueCollision(listOf(key, value, plain), listOf("真"))
        assertEquals(listOf("真", "真", "name"), ordered.map { it.label })
        assertEquals(CompletionProvider.CompletionType.VALUE, ordered[0].type, "VALUE 项应排最前")
    }

    @Test
    fun emptyValueOrderReturnsUnchanged() {
        val key = item("if", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        val plain = item("name", CompletionProvider.CompletionType.KEY)
        val items = listOf(key, plain)
        assertEquals(items, orderByValueCollision(items, emptyList()))
    }

    @Test
    fun stableOrderWithinSameGroup() {
        val a = item("x", CompletionProvider.CompletionType.KEY).copy(category = listOf("核心"))
        val b = item("y", CompletionProvider.CompletionType.KEY).copy(category = listOf("核心"))
        assertEquals(listOf(a, b), orderByValueCollision(listOf(a, b), listOf("真")))
    }

    @Test
    fun translationLibraryKeyBeatsValueOnSameLabel() {
        val value = item("自身资源", CompletionProvider.CompletionType.VALUE).copy(detail = "LogicBoolean")
        val tlib = item("自身资源", CompletionProvider.CompletionType.KEY).copy(detail = "翻译库", category = listOf("翻译库"))
        val deduped = dedupeValueByKeyPriority(listOf(value), listOf(value, tlib))
        assertEquals(listOf(tlib), deduped, "翻译库 KEY 应压过同名 VALUE")
    }

    @Test
    fun valueContextFilterKeepsTranslationLibraryUnconditionally() {
        val tlib = item("自身资源", CompletionProvider.CompletionType.KEY).copy(detail = "翻译库", category = listOf("翻译库"))
        val plain = item("自身资源类型", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        assertEquals(listOf(tlib, plain), filterValueContextItems(listOf(tlib, plain)), "翻译库项在值上下文无条件保留")
    }

    @Test
    fun fullMergePrefersTranslationLibraryOverValue() {
        val value = item("自身资源", CompletionProvider.CompletionType.VALUE).copy(detail = "LogicBoolean")
        val tlib = item("自身资源", CompletionProvider.CompletionType.KEY).copy(detail = "翻译库", category = listOf("翻译库"))
        val others = item("自身资源类型", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        // 模拟 finalResults = valueResults + filterValueContextItems(results)
        val valueResults = listOf(value)
        val filtered = filterValueContextItems(listOf(tlib, others))
        val finalResults = valueResults + filtered
        val deduped = dedupeValueByKeyPriority(valueResults, finalResults)
        val ordered = orderByValueCollision(deduped, valueResults.map { it.label })
        assertEquals(listOf("自身资源", "自身资源类型"), ordered.map { it.label })
        assertEquals("翻译库", ordered[0].detail, "翻译库项应压过同名 VALUE")
    }

    // ===== 逻辑值入口抑制：真/假/if 在同一值内只出现一次 =====

    @Test
    fun entryTokenDetection() {
        assertFalse(logicValueHasEntryToken("自动触发:"), "空前缀无入口 token")
        assertTrue(logicValueHasEntryToken("自动触发: if"), "if 出现 → 抑制")
        assertTrue(logicValueHasEntryToken("自动触发: if 自身在天上()"), "if 后接条件仍抑制")
        assertTrue(logicValueHasEntryToken("自动触发: 真"), "真 出现 → 抑制")
        assertTrue(logicValueHasEntryToken("需要条件: 假"), "假 出现 → 抑制")
        assertTrue(logicValueHasEntryToken("自动触发: true"), "英文 true 出现 → 抑制")
        assertFalse(logicValueHasEntryToken("自动触发: 自身在天上() and "), "无入口 token 不抑制")
        assertFalse(logicValueHasEntryToken("自动触发: 自身资源.gold"), "资源引用不误判")
    }

    @Test
    fun entryTokenFilterSuppressesThreeWhenOn() {
        val ifItem = item("if", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        val zhen = item("真", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        val jia = item("假", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        val andItem = item("and", CompletionProvider.CompletionType.KEY).copy(category = listOf("values"))
        val fn = item("自身在天上", CompletionProvider.CompletionType.VALUE)
        val items = listOf(ifItem, zhen, jia, andItem, fn)
        val filtered = filterLogicEntryTokens(items, true)
        assertEquals(listOf("and", "自身在天上"), filtered.map { it.label }, "抑制时去掉 真/假/if，保留其它")
    }

    @Test
    fun entryTokenFilterOffReturnsUnchanged() {
        val items = listOf(
            item("if", CompletionProvider.CompletionType.KEY),
            item("真", CompletionProvider.CompletionType.KEY)
        )
        assertEquals(items, filterLogicEntryTokens(items, false))
    }
}