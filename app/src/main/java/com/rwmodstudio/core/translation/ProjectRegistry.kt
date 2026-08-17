package com.rwmodstudio.core.translation

import com.rwmodstudio.core.RwmodPaths
import java.io.File

/**
 * 项目通行证管理
 * 记录已通过查重的项目名称
 */
object ProjectRegistry {

    private const val FILENAME = "project_registry.txt"

    /**
     * 获取注册文件
     */
    private fun getRegistryFile(): File = RwmodPaths.projectRegistryFile

    /**
     * 获取所有已注册的项目名称
     */
    fun getRegisteredProjects(): Set<String> {
        val file = getRegistryFile()
        if (!file.exists()) return emptySet()
        return try {
            file.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * 检查项目是否已注册
     */
    fun isProjectRegistered(projectName: String): Boolean {
        return getRegisteredProjects().contains(projectName)
    }

    /**
     * 注册项目（通过查重后）
     */
    fun registerProject(projectName: String) {
        val file = getRegistryFile()
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) {
            try {
                file.readLines(Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } catch (e: Exception) {
                emptySet<String>()
            }
        } else {
            emptySet<String>()
        }
        if (!existing.contains(projectName)) {
            file.appendText("$projectName\n", Charsets.UTF_8)
        }
    }

    /**
     * 获取注册文件路径（用于显示）
     */
    fun getRegistryPath(): String {
        return getRegistryFile().absolutePath
    }
}
