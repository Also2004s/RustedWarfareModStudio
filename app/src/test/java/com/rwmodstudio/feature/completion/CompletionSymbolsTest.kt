package com.rwmodstudio.feature.completion

import com.rwmodstudio.core.ProjectTagScanner
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionSymbolsTest {

    @BeforeTest
    fun resetProjectScannerCache() {
        // 避免其他测试（如 scanIfNeeded）写入的静态缓存污染 extractFileSymbols 断言
        ProjectTagScanner.resetCacheForTests()
    }

    @Test
    fun extractFileSymbolsParsesAllCategories() {
        val text = """
            [core]
            name: 单位A
            tags: tag1, tag2
            addGlobalTag: global1
            @memory 攻击目标:unit
            [action_alert]
            带标签发送消息: hitZone, 目标匹配
            sendMessageWithTags: fish
            [resource_铁矿]
            [global_resource_金库]
            @memory hp:int
            defineUnitMemory: boolean flag1, float speed1, unit[] targets
        """.trimIndent()
        val info = extractFileSymbols(text)
        assertTrue("单位A" in info.unitNames, "unit name should be extracted")
        assertTrue("tag1" in info.tags && "tag2" in info.tags, "local tags should be extracted")
        assertTrue("global1" in info.globalTags, "global tags should be extracted")
        assertTrue("hitZone" in info.messageTags && "目标匹配" in info.messageTags, "chinese 带标签发送消息 values should be extracted as message tags")
        assertTrue("fish" in info.messageTags, "english sendMessageWithTags value should be extracted as message tag")
        assertTrue("hitZone" !in info.tags, "message tags must stay in their own namespace")
        assertTrue("铁矿" in info.resources, "local resources should be extracted")
        assertTrue("金库" in info.globalResources, "global resources should be extracted")
        assertTrue("hp" in info.memories, "@memory should be extracted")
        assertTrue("flag1" in info.memories && "speed1" in info.memories, "defineUnitMemory vars should be extracted")
        // 内存类型
        assertEquals("unit", info.memoryTypes["攻击目标"], "@memory 攻击目标:unit 类型应为 unit")
        assertEquals("int", info.memoryTypes["hp"], "@memory hp:int 类型应为 int")
        assertEquals("boolean", info.memoryTypes["flag1"], "defineUnitMemory boolean flag1 类型应为 boolean")
        assertEquals("unit[]", info.memoryTypes["targets"], "defineUnitMemory unit[] targets 类型应为 unit[]")
    }

    @Test
    fun extractFileSymbolsDefinesGlobalAndSectionLocal() {
        val text = """
            [core]
            @global fw:10
            @global also:5
            name: 单位B
            [行动_blink]
            @define slotId:3
            发射弹: ${'$'}{slotId}
            [行动_dance]
            @define dir:90
            发射弹: ${'$'}{dir}
        """.trimIndent()
        val info = extractFileSymbols(text)
        // 全局变量（@global）：项目级收集
        assertTrue("fw" in info.globalVariables && "also" in info.globalVariables, "@global 变量应收集进 globalVariables")
        // 局部变量（@define）：按节归属
        assertEquals(setOf("slotId"), info.sectionDefines["行动_blink".lowercase()], "@define 应归属定义所在节")
        assertEquals(setOf("dir"), info.sectionDefines["行动_dance".lowercase()])
        // 局部变量不跨节、不进全局变量
        assertTrue("slotId" !in info.globalVariables && "dir" !in info.globalVariables, "@define 不应进 globalVariables")
        assertTrue(info.sectionDefines["core"]?.isEmpty() != false, "core 节无 @define 应无记录")
    }

    @Test
    fun mergeCompletionSymbolsMergesVariables() {
        val current = extractCurrentFileSymbols(
            """
            [核心]
            @global localOnly:1
            @define curVar:2
            价格: 10
            """.trimIndent()
        )
        val chain = extractCurrentFileSymbols(
            """
            [核心]
            @global chainOnly:3
            @define inherited:4
            价格: 10
            """.trimIndent()
        )
        val merged = mergeCompletionSymbols(current, chain)
        assertTrue("localOnly" in merged.globalVariables && "chainOnly" in merged.globalVariables, "全局变量并集")
        assertEquals(setOf("curVar", "inherited"), merged.sectionDefines["核心".lowercase()], "同节 @define 并集")
    }

    @Test
    fun extractFileSymbolsEmptyText() {
        val info = extractFileSymbols("")
        assertTrue(info.tags.isEmpty())
        assertTrue(info.globalTags.isEmpty())
        assertTrue(info.messageTags.isEmpty())
        assertTrue(info.resources.isEmpty())
        assertTrue(info.globalResources.isEmpty())
        assertTrue(info.memories.isEmpty())
        assertTrue(info.unitNames.isEmpty())
    }

    // ---- 中文视图 / 翻译键值可编辑性（不硬编码中文节名/键名） ----

    @Test
    fun extractFileSymbolsParsesChineseViewCoreSection() {
        val sectionZhToEn = mapOf("核心" to "core")
        val text = """
            [核心]
            name: 单位A
            tags: tag1
        """.trimIndent()
        val info = extractFileSymbols(
            text,
            sectionToEnglish = { sectionZhToEn[it] ?: it },
            keyToEnglish = { it }
        )
        assertTrue("单位A" in info.unitNames, "Chinese-view [核心] + name: should be extracted")
        assertTrue("tag1" in info.tags, "tags should still be extracted")
    }

    @Test
    fun extractFileSymbolsParsesTranslatedNameKey() {
        val sectionZhToEn = mapOf("核心" to "core")
        val keyZhToEn = mapOf("名称" to "name")
        val text = """
            [核心]
            名称: 单位B
        """.trimIndent()
        val info = extractFileSymbols(
            text,
            sectionToEnglish = { sectionZhToEn[it] ?: it },
            keyToEnglish = { keyZhToEn[it] ?: it }
        )
        assertTrue("单位B" in info.unitNames, "translated key 名称: should be extracted")
    }

    @Test
    fun extractFileSymbolsFollowsChangedTranslationKeys() {
        // 模拟用户修改翻译键值：核心改成「核心配置」、name 翻译成「名称」
        val sectionZhToEn = mapOf("核心配置" to "core")
        val keyZhToEn = mapOf("名称" to "name")
        val text = """
            [核心配置]
            名称: 单位C
        """.trimIndent()
        val info = extractFileSymbols(
            text,
            sectionToEnglish = { sectionZhToEn[it] ?: it },
            keyToEnglish = { keyZhToEn[it] ?: it }
        )
        assertTrue("单位C" in info.unitNames, "should follow user-edited translation keys")
    }

    @Test
    fun extractFileSymbolsIgnoresNonNameKeysInCore() {
        val text = """
            [core]
            displayName: 显示名
            name: 单位D
        """.trimIndent()
        val info = extractFileSymbols(text)
        assertTrue("单位D" in info.unitNames)
        assertTrue("显示名" !in info.unitNames, "displayName value must not be treated as unit name")
    }

    @Test
    fun extractFileSymbolsEnglishStillWorksByDefault() {
        val text = """
            [core]
            name: 单位E
        """.trimIndent()
        val info = extractFileSymbols(text)
        assertTrue("单位E" in info.unitNames, "English [core] + name: must still work with default normalizers")
    }

    // ---- 二次增强：命名节（炮塔/抛射体/效果/行动/动画/贴花/附属/可建造）节名收集 ----

    @Test
    fun extractFileSymbolsParsesNamedSections() {
        val text = """
            [turret_mainGun]
            [projectile_shell]
            [effect_爆炸]
            [action_fire]
            [hiddenAction_auto]
            [animation_walk]
            [decal_痕迹]
            [attachment_wing]
            [canBuild_tank]
            [炮塔_副炮]
        """.trimIndent()
        val info = extractFileSymbols(text)
        assertTrue("mainGun" in info.sectionNames["turret"].orEmpty(), "turret section name should be extracted")
        assertTrue("副炮" in info.sectionNames["turret"].orEmpty(), "Chinese [炮塔_副炮] should map to turret")
        assertTrue("shell" in info.sectionNames["projectile"].orEmpty())
        assertTrue("爆炸" in info.sectionNames["effect"].orEmpty())
        assertTrue("fire" in info.sectionNames["action"].orEmpty())
        assertTrue("auto" in info.sectionNames["hiddenaction"].orEmpty())
        assertTrue("walk" in info.sectionNames["animation"].orEmpty())
        assertTrue("痕迹" in info.sectionNames["decal"].orEmpty())
        assertTrue("wing" in info.sectionNames["attachment"].orEmpty())
        assertTrue("tank" in info.sectionNames["canbuild"].orEmpty())
    }

    @Test
    fun mergeCompletionSymbolsMergesSectionNames() {
        val current = extractFileSymbols("[turret_a]")
        val chain = ProjectTagScanner.scanChainLines(listOf("[turret_b]", "[effect_c]", "[turret_a]"))
        val merged = mergeCompletionSymbols(current, chain)
        val turrets = merged.sectionNames["turret"].orEmpty()
        assertTrue("a" in turrets, "current file turret should be kept")
        assertTrue("b" in turrets, "chain turret should be merged")
        assertTrue("c" in merged.sectionNames["effect"].orEmpty(), "chain effect should be merged")
    }

    @Test
    fun mergeCompletionSymbolsMergesMemoryTypes() {
        val current = extractFileSymbols("[core]\n@memory 当前单位:unit\n")
        val chain = ProjectTagScanner.scanChainLines(listOf("[core]\n@memory 父单位:unit\n@memory 当前单位:int\n"))
        val merged = mergeCompletionSymbols(current, chain)
        assertEquals("unit", merged.memoryTypes["当前单位"], "current file 类型应优先")
        assertEquals("unit", merged.memoryTypes["父单位"], "chain 类型应合并")
    }

    // ---- 数据源修正：节名引用只取「当前文件 + 继承链」，不合并项目全量节名 ----

    @Test
    fun extractFileSymbolsDoesNotMergeProjectSectionNames() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_sym_${System.nanoTime()}")
        dir.mkdirs()
        try {
            // 项目缓存：其他文件里定义的节 + 标签 + 消息标签
            File(dir, "other.ini").writeText(
                "[core]\nname: 别家单位\ntags: projTag\n[action_alert]\n带标签发送消息: projMsg\n[turret_projectOnly]\n"
            )
            ProjectTagScanner.scan(dir)
            val info = extractFileSymbols("[core]\nname: 本机单位\ntags: fileTag\n带标签发送消息: fileMsg\n[turret_fileOnly]\n")
            // 节名：仅当前文件（项目全量不再合并进 sectionNames）
            assertTrue("fileOnly" in info.sectionNames["turret"].orEmpty(), "本文件节名应保留")
            assertTrue("projectOnly" !in info.sectionNames["turret"].orEmpty(), "项目全量节名不应合并进 sectionNames")
            // 标签：仍合并项目缓存（跨文件标签引用合法）
            assertTrue("fileTag" in info.tags && "projTag" in info.tags, "tags 仍应合并项目缓存")
            // 消息标签：同样合并项目缓存（跨文件 带标签发送消息 引用合法）
            assertTrue("fileMsg" in info.messageTags && "projMsg" in info.messageTags, "messageTags 仍应合并项目缓存")
        } finally {
            dir.deleteRecursively()
        }
    }
}
