package com.rwmodstudio.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ProjectTagScanner.scanIfNeeded 按根目录缓存行为测试。
 */
class ProjectTagScannerScanTest {

    @AfterTest
    fun resetCache() {
        ProjectTagScanner.resetCacheForTests()
    }

    private fun tempRoot(name: String, unitName: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_scan_${name}_${System.nanoTime()}")
        dir.mkdirs()
        val units = File(dir, "units")
        units.mkdirs()
        File(units, "unit.ini").writeText("[core]\nname: $unitName\n")
        return dir
    }

    @Test
    fun scanCollectsNamedSections() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_scan_sec_${System.nanoTime()}")
        dir.mkdirs()
        try {
            File(dir, "a.ini").writeText("[turret_mainGun]\n[effect_爆炸]\n[hiddenAction_auto]\n")
            File(dir, "b.ini").writeText("[炮塔_副炮]\n[projectile_shell]\n")
            val info = ProjectTagScanner.scan(dir)
            assertTrue("mainGun" in info.sectionNames["turret"].orEmpty(), "english turret section")
            assertTrue("副炮" in info.sectionNames["turret"].orEmpty(), "chinese 炮塔 section maps to turret")
            assertTrue("爆炸" in info.sectionNames["effect"].orEmpty(), "effect section")
            assertTrue("auto" in info.sectionNames["hiddenaction"].orEmpty(), "hiddenAction section")
            assertTrue("shell" in info.sectionNames["projectile"].orEmpty(), "projectile section")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun scanIfNeededCachesByRoot() {
        val root1 = tempRoot("r1", "TankA")
        val root2 = tempRoot("r2", "TankB")
        try {
            val first = ProjectTagScanner.scanIfNeeded(root1)
            assertTrue("TankA" in first.unitNames, "scan should collect [core] name: from disk")
            assertEquals(root1.absolutePath, ProjectTagScanner.getScannedRoot())

            // 同 root 二次调用返回同一缓存实例，不重扫
            val second = ProjectTagScanner.scanIfNeeded(root1)
            assertSame(first, second, "same root should reuse cached scan result")

            // 换 root 自动重扫
            val third = ProjectTagScanner.scanIfNeeded(root2)
            assertTrue("TankB" in third.unitNames, "new root should rescan")
            assertEquals(root2.absolutePath, ProjectTagScanner.getScannedRoot())
        } finally {
            root1.deleteRecursively()
            root2.deleteRecursively()
        }
    }

    @Test
    fun scanCollectsMessageTags() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_scan_msg_${System.nanoTime()}")
        dir.mkdirs()
        try {
            File(dir, "a.ini").writeText("[action_alert]\nsendMessageWithTags: hitZone, target\n带标签发送消息: 目标匹配\n")
            val info = ProjectTagScanner.scan(dir)
            assertTrue("hitZone" in info.messageTags, "english sendMessageWithTags value should be collected")
            assertTrue("target" in info.messageTags, "comma-separated values should be split")
            assertTrue("目标匹配" in info.messageTags, "chinese 带标签发送消息 value should be collected")
            // 消息标签不进普通 tags（独立命名空间）
            assertTrue(info.tags.isEmpty(), "message tags must not pollute local tags")
            // 引用位置可跳转
            val refs = info.references["hitZone"].orEmpty()
            assertTrue(refs.isNotEmpty(), "message tag reference should be recorded")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun scanCollectsMemoryTypes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_scan_mt_${System.nanoTime()}")
        dir.mkdirs()
        try {
            File(dir, "a.ini").writeText(
                "[core]\n@memory 攻击目标:unit\n@memory hp:int\n" +
                    "defineUnitMemory: float speed1, unit[] targets\n"
            )
            val info = ProjectTagScanner.scan(dir)
            assertEquals("unit", info.memoryTypes["攻击目标"], "@memory 攻击目标:unit 类型应为 unit")
            assertEquals("int", info.memoryTypes["hp"], "@memory hp:int 类型应为 int")
            assertEquals("float", info.memoryTypes["speed1"], "defineUnitMemory float speed1 类型应为 float")
            assertEquals("unit[]", info.memoryTypes["targets"], "defineUnitMemory unit[] targets 类型应为 unit[]")
            assertTrue("攻击目标" in info.memories && "targets" in info.memories, "内存名仍应收集")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun scanCollectsGlobalAndSectionVariables() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_scan_var_${System.nanoTime()}")
        dir.mkdirs()
        try {
            File(dir, "a.ini").writeText(
                "[core]\n@global fw:10\n@global also:5\nname: A\n" +
                    "[行动_blink]\n@define slotId:3\n发射弹: ${'$'}{slotId}\n" +
                    "[行动_dance]\n@define dir:90\n"
            )
            File(dir, "b.ini").writeText("[core]\n@global neo:1\n")
            val info = ProjectTagScanner.scan(dir)
            // @global 项目级收集（跨文件）
            assertTrue("fw" in info.globalVariables && "also" in info.globalVariables && "neo" in info.globalVariables)
            // @define 按节归属（小写节名）
            assertEquals(setOf("slotId"), info.sectionDefines["行动_blink".lowercase()])
            assertEquals(setOf("dir"), info.sectionDefines["行动_dance".lowercase()])
            // @define 不进全局变量、@global 不进节局部
            assertTrue("slotId" !in info.globalVariables && "dir" !in info.globalVariables)
            assertTrue(info.sectionDefines["core"]?.isEmpty() != false)
        } finally {
            dir.deleteRecursively()
        }
    }
}
