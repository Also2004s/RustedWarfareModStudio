package com.rwmodstudio.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionComparatorTest {

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "rwmod_test_${name}_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun compareFoldersDetectsCommonDiffAndUniqueFiles() {
        val root = tempDir("root")
        val meta = tempDir("meta")
        try {
            File(root, "units.ini").writeText("[core]\nname: A\n")
            File(meta, "units.ini").writeText("[core]\nname: B\n")
            File(root, "only_root.ini").writeText("[core]\nname: R\n")
            File(meta, "only_meta.template").writeText("[graphics]\nimage: x.png\n")

            val result = VersionComparator.compareFolders(root, meta)
            assertTrue(result.commonFiles.any { it.filePath == "units.ini" }, "should report common diff")
            assertEquals(listOf("only_root.ini"), result.rootOnlyFiles)
            assertEquals(listOf("only_meta.template"), result.metaOnlyFiles)
            val units = result.commonFiles.first { it.filePath == "units.ini" }
            assertTrue(units.removedCount >= 1 || units.addedCount >= 1)
        } finally {
            root.deleteRecursively()
            meta.deleteRecursively()
        }
    }

    @Test
    fun compareFoldersIdenticalReturnsNoCommonDiff() {
        val root = tempDir("root2")
        val meta = tempDir("meta2")
        try {
            File(root, "a.ini").writeText("[core]\nname: X\n")
            File(meta, "a.ini").writeText("[core]\nname: X\n")
            val result = VersionComparator.compareFolders(root, meta)
            assertTrue(result.commonFiles.isEmpty(), "identical files should not be reported")
            assertTrue(result.rootOnlyFiles.isEmpty())
            assertTrue(result.metaOnlyFiles.isEmpty())
        } finally {
            root.deleteRecursively()
            meta.deleteRecursively()
        }
    }
}