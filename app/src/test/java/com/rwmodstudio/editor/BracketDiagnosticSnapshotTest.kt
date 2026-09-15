package com.rwmodstudio.editor

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 括号诊断的全文快照回归测试。
 *
 * 背景：sora 的 ContentReference.toString() 走 TextReference.toString()，按**构造时**固定的长度切片，
 * 文本删短后取快照会抛 StringIndexOutOfBoundsException（诊断被静默吞掉），文本变长后会被截掉尾部。
 * 括号诊断必须用 bracketSnapshot（底层 Content 的当前全文），否则表现为：删除括号后不再诊断、角标不消失。
 */
class BracketDiagnosticSnapshotTest {

    @Test
    fun snapshotFollowsTextShrinkAndGrow() {
        val content = Content("a=()\n")
        val ref = ContentReference(content)
        assertEquals("a=()\n", bracketSnapshot(ref))

        content.delete(3, 4) // 删掉 ')'
        assertEquals("a=(\n", bracketSnapshot(ref))

        content.insert(0, 0, "x")
        assertEquals("xa=(\n", bracketSnapshot(ref))
    }

    @Test
    fun contentReferenceToStringBreaksAfterDeletion() {
        val content = Content("a=()\n")
        val ref = ContentReference(content)
        content.delete(3, 4)
        val failure = runCatching { ref.toString() }.exceptionOrNull()
        assertTrue(
            failure is StringIndexOutOfBoundsException,
            "本测试固化 sora 行为：ContentReference.toString() 在文本删短后抛异常，故必须用 bracketSnapshot"
        )
    }

    @Test
    fun deletedCloseBracketIsReportedOnSnapshot() {
        val content = Content("[core]\nkey:func(a, b)\n")
        val ref = ContentReference(content)
        val closeIndex = content.toString().lastIndexOf(')')
        content.delete(closeIndex, closeIndex + 1)
        val issues = BracketScanner.scan(bracketSnapshot(ref))
        assertEquals(1, issues.size)
        assertTrue(issues[0].message.contains("未闭合"))
    }
}
