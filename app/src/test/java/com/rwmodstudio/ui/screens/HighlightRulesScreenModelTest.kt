package com.rwmodstudio.ui.screens

import com.rwmodstudio.core.HighlightRuleManager
import com.rwmodstudio.core.HighlightRulesDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 规则页骨架：内置语法把同一批规则在「键值对块内」和「顶层」各写了一遍，顶层那份多数只是写法不同
 * （如比较符多一个 `=`）。规则页默认**折叠**这些 `(顶层)` 分组，只影响显示、规则照旧生效。
 */
class HighlightRulesScreenModelTest {

    private fun rule(pattern: String) = HighlightRuleManager.HighlightRule(
        pattern,
        HighlightRuleManager.RuleType.REGEX
    )

    private val actual = listOf(
        HighlightRuleManager.HighlightRuleCategory("operator", listOf(rule("a")), "比较符"),
        HighlightRuleManager.HighlightRuleCategory("operator", listOf(rule("b")), "比较符(顶层)"),
        HighlightRuleManager.HighlightRuleCategory("value", listOf(rule("c")), "自身检测"),
        HighlightRuleManager.HighlightRuleCategory("comment", listOf(rule("d")), "行注释"),
        HighlightRuleManager.HighlightRuleCategory("team", emptyList(), "队伍值")
    )

    @Test
    fun topLevelGroupsAreFoldedByDefault() {
        val vm = buildScreenItems(actual, showTopLevel = false)
        val operator = vm.first { it.categoryId == "operator" }
        assertEquals(listOf("比较符"), operator.lists.map { it.listName }, "顶层分组应被折叠")
        assertEquals(1, operator.hiddenTopLevel, "应报告被折叠的顶层分组数")
        // 其它类别不受影响
        assertEquals(listOf("自身检测"), vm.first { it.categoryId == "value" }.lists.map { it.listName })
    }

    @Test
    fun nonScopeCategoriesExposeNoRuleEntry() {
        // UI 高亮 / 普通文本不参与 scope 着色（列表里没有 scope），界面只展示说明、不给「+ 规则」入口
        val vm = buildScreenItems(actual, showTopLevel = false)
        listOf("ui", "plainText").forEach { id ->
            val info = HighlightRuleManager.availableCategories.first { it.id == id }
            assertEquals("", info.scope, "$id 不应带 scope")
        }
        // 其它类别照旧是 scope 类别（有 scope 才能写规则）
        assertTrue(vm.first { it.categoryId == "value" }.scope.isNotBlank())
    }

    @Test
    fun enablingTheToggleShowsTopLevelGroupsAgain() {
        val vm = buildScreenItems(actual, showTopLevel = true)
        val operator = vm.first { it.categoryId == "operator" }
        assertEquals(listOf("比较符", "比较符(顶层)"), operator.lists.map { it.listName })
        assertEquals(0, operator.hiddenTopLevel)
    }

    @Test
    fun categoryWithOnlyTopLevelGroupsStillUsesAnEmptyPlaceholder() {
        // 折叠后一个分组都不剩的类别，仍要有「还没有规则」占位 + 「+ 规则」入口，
        // 否则用户没法给这个高亮类型加规则（历史要求：空的类别也必须显示）
        val onlyTop = listOf(
            HighlightRuleManager.HighlightRuleCategory("team", listOf(rule("x")), "路径点类型(顶层)")
        )
        val vm = buildScreenItems(onlyTop, showTopLevel = false)
        val team = vm.first { it.categoryId == "team" }
        assertEquals(listOf(""), team.lists.map { it.listName }, "应回落到空占位组")
        assertEquals(1, team.hiddenTopLevel)
    }

    @Test
    fun categoriesWithoutRulesKeepTheEmptyPlaceholder() {
        val vm = buildScreenItems(actual, showTopLevel = false)
        val empty = vm.first { it.categoryId == "movement" } // actual 里没有 → 空类别
        assertEquals(listOf(""), empty.lists.map { it.listName })
        assertEquals(0, empty.hiddenTopLevel)
        assertTrue(empty.lists.single().rules.isEmpty())
    }

    @Test
    fun foldMarkersAreTopLevelAndRepeatDefinition() {
        // 折叠判定只看后缀，避免误伤用户自己起的、正好含「顶层」两字的分组名。
        // (顶层) 与 (重复定义) 都默认折叠 —— 只藏显示，规则照旧生效（2026-09-18 老板要求）。
        assertTrue(HighlightRulesDefaults.isTopLevelGroup("比较符(顶层)"))
        assertTrue(
            HighlightRulesDefaults.isTopLevelGroup("布尔值(重复定义)"),
            "重复定义与顶层一样默认折叠（只藏显示，规则照旧生效）"
        )
        assertTrue(!HighlightRulesDefaults.isTopLevelGroup("顶层值"))
        assertTrue(!HighlightRulesDefaults.isTopLevelGroup("比较符"))
    }
}
