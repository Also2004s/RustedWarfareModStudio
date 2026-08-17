package com.rwmodstudio.core

import com.rwmodstudio.feature.completion.mergeCompletionSymbols
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 继承链符号补全（资源/全局资源/内存）相关测试。
 * 纯 JVM 测试：通过 InheritanceResolver.resolveMergedLines（内容提供者注入）覆盖
 * resolveSymbols 的链合并语义，避免依赖 Android 的磁盘缓存（InheritanceCache/RwmodPaths）。
 */
class InheritanceChainSymbolsTest {

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_chain_${name}_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent() + "\n")
    }

    @Test
    fun scanChainLinesParsesResourcesAndMemories() {
        val info = ProjectTagScanner.scanChainLines(
            listOf(
                "# 注释行",
                "// 注释行",
                "",
                "[resource_Iron]",
                "[资源_铜矿]",
                "[global_resource_Gold]",
                "[全局资源_金库]",
                "[globalResource_Silver]",
                "[core]",
                "@memory hp:int",
                "defineUnitMemory: boolean flag1, float speed1",
                "定义单位内存: 布尔 alive2",
                "普通属性: 不相关"
            )
        )
        assertEquals(setOf("Iron", "铜矿"), info.resources)
        assertEquals(setOf("Gold", "金库", "Silver"), info.globalResources)
        assertEquals(setOf("hp", "flag1", "speed1", "alive2"), info.memories)
        assertTrue(info.tags.isEmpty())
        assertTrue(info.globalTags.isEmpty())
        assertTrue(info.unitNames.isEmpty())
    }

    @Test
    fun scanChainLinesSkipsCommentsAndBlankLines() {
        val info = ProjectTagScanner.scanChainLines(
            listOf("# [resource_注释资源]", "// @memory fake:int", "", "  ", "[resource_Real]")
        )
        assertEquals(setOf("Real"), info.resources)
        assertTrue(info.memories.isEmpty())
    }

    @Test
    fun resolveMergedLinesIncludesTemplateCopyFromAndSelf() {
        val root = tempDir("root")
        try {
            write(
                File(root, "all-units.template"),
                """
                [core]
                name: Template

                [global_resource_Gold]
                @memory tmplHp:int
                defineUnitMemory: boolean tmplFlag, float tmplSpeed
                """.trimIndent()
            )
            write(
                File(root, "base.ini"),
                """
                [core]
                name: BaseUnit

                [resource_Iron]
                @memory baseMem:int
                """.trimIndent()
            )
            write(
                File(root, "child.ini"),
                """
                copyFrom: base.ini
                [core]
                name: ChildUnit

                [resource_Steel]
                @memory childMem:int
                """.trimIndent()
            )

            val merged = InheritanceResolver.resolveMergedLines(
                File(root, "child.ini").absolutePath,
                root.absolutePath
            ) { it.readText() }
            assertTrue(merged != null, "chain should resolve")
            val info = ProjectTagScanner.scanChainLines(merged!!.map { it.content })

            assertEquals(setOf("Iron", "Steel"), info.resources, "resources = base + self")
            assertEquals(setOf("Gold"), info.globalResources, "global resources = template")
            assertEquals(
                setOf("tmplHp", "tmplFlag", "tmplSpeed", "baseMem", "childMem"),
                info.memories,
                "memories = template + base + self"
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergeCompletionSymbolsUnionsAndDedupes() {
        fun info(
            resources: Set<String>,
            globalResources: Set<String>,
            memories: Set<String>
        ) = ProjectTagScanner.ProjectTagInfo(
            tags = emptySet(),
            globalTags = emptySet(),
            resources = resources,
            globalResources = globalResources,
            memories = memories,
            unitNames = emptySet(),
            references = emptyMap()
        )

        val current = info(
            resources = setOf("Iron", "Steel"),
            globalResources = setOf("Gold"),
            memories = setOf("childMem", "baseMem")
        )
        val chain = info(
            resources = setOf("Iron", "Copper"),
            globalResources = setOf("Gold", "Silver"),
            memories = setOf("tmplHp", "baseMem")
        )

        val merged = mergeCompletionSymbols(current, chain)
        assertEquals(setOf("Iron", "Steel", "Copper"), merged.resources)
        assertEquals(setOf("Gold", "Silver"), merged.globalResources)
        assertEquals(setOf("childMem", "baseMem", "tmplHp"), merged.memories)

        // chain == null 时原样返回
        val same = mergeCompletionSymbols(current, null)
        assertEquals(current, same)
    }
}
