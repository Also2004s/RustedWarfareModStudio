package com.rwmodstudio.core.translation

import android.content.Context
import android.util.Log
import com.rwmodstudio.core.RwmodPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 代码参考库
 * 基于代码参考库.json，提供属性文档和补全
 */
class CodeReferenceRepository {

    private companion object {
        const val TAG = "CodeReferenceRepository"
    }

    @Serializable
    data class PropertyInfo(
        val name: String = "",
        val type: String = "",
        val description: String = "",
        val version: String = "",
        val isOutdated: Boolean = false,
        val example: String = "",
        val name_en: String? = null,
        val desc_zh: String = "",
        /** 清洗后的默认补全值（values 类条目由来源表提供，如 队伍中此单位数量(）；空表示走规则兜底 */
        val default: String = ""
    )

    @Serializable
    data class SectionData(
        val data: List<PropertyInfo> = emptyList()
    )

    @Serializable
    data class CodeReference(
        val sections: Map<String, SectionData> = emptyMap(),
        val values: Map<String, ValueCategory> = emptyMap()
    )

    @Serializable
    data class ValueCategory(
        val name: String = "",
        val example: String = "",
        val data: List<PropertyInfo> = emptyList()
    )

    private var codeReference: CodeReference? = null
    private val sectionProperties = mutableMapOf<String, List<PropertyInfo>>()
    private val valueCategories = mutableMapOf<String, ValueCategory>()
    private val realSectionNames = mutableSetOf<String>()

    val isLoaded: Boolean get() = codeReference != null

    /**
     * 加载代码参考库。
     * 优先使用 App files 目录下运行时生成的 code_reference.json，不存在时回退到 assets。
     */
    suspend fun loadFromAssets(context: Context) = withContext(Dispatchers.IO) {
        try {
            val filesRef = RwmodPaths.codeReferenceFile
            val jsonString = if (filesRef.exists()) {
                filesRef.readText(Charsets.UTF_8)
            } else {
                val inputStream = context.assets.open("data/code_reference.json")
                val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                val text = reader.readText()
                reader.close()
                text
            }

            val json = Json { ignoreUnknownKeys = true }
            codeReference = json.decodeFromString<CodeReference>(jsonString)

            // 构建节→属性映射
            codeReference?.sections?.forEach { (sectionName, sectionData) ->
                sectionProperties[sectionName] = sectionData.data
                realSectionNames.add(sectionName)
            }
            codeReference?.values?.forEach { (key, cat) ->
                val hasType = cat.data.any { it.type.isNotEmpty() }
                if (hasType) {
                    sectionProperties[key] = cat.data
                } else {
                    valueCategories[key] = cat
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load code reference", e)
        }
    }

    /**
     * 获取指定节的所有属性
     */
    fun getPropertiesForSection(sectionName: String): List<PropertyInfo> {
        return sectionProperties[sectionName] ?: emptyList()
    }

    /**
     * 根据名称搜索属性（支持模糊匹配）
     */
    fun searchProperties(query: String, sectionName: String? = null): List<PropertyInfo> {
        val results = mutableListOf<PropertyInfo>()
        val lowerQuery = query.lowercase()

        if (sectionName != null) {
            val properties = getPropertiesForSection(sectionName)
            results.addAll(properties.filter {
                it.name.lowercase().contains(lowerQuery) ||
                it.name_en?.lowercase()?.contains(lowerQuery) == true ||
                it.desc_zh.contains(query)
            })
        } else {
            sectionProperties.values.forEach { properties ->
                results.addAll(properties.filter {
                    it.name.lowercase().contains(lowerQuery) ||
                    (it.name_en?.lowercase()?.contains(lowerQuery) == true) ||
                    it.desc_zh.contains(query) ||
                    it.type.lowercase().contains(lowerQuery)
                })
            }
        }
        return results.distinctBy { it.name }
    }

    /**
     * 获取属性的详细文档
     */
    fun getPropertyDocumentation(property: PropertyInfo): String {
        val sb = StringBuilder()
        sb.appendLine("**名称:** ${property.name}")
        if (property.name_en != null) {
            sb.appendLine("**英文:** ${property.name_en}")
        }
        sb.appendLine("**类型:** `${property.type}`")
        if (property.version.isNotEmpty()) {
            sb.appendLine("**版本:** ${property.version}")
        }
        if (property.desc_zh.isNotEmpty()) {
            sb.appendLine("**说明:** ${property.desc_zh}")
        }
        if (property.example.isNotEmpty()) {
            sb.appendLine("**示例:**")
            sb.appendLine("```ini")
            sb.appendLine(property.example)
            sb.appendLine("```")
        }
        if (property.isOutdated) {
            sb.appendLine("**已过时:** 是")
        }
        return sb.toString()
    }

    /**
     * 获取所有节名称（包含 values 中有 type 的类别，仅供搜索/翻译遍历使用）
     */
    fun getAllSectionNames(): Set<String> = sectionProperties.keys

    /**
     * 获取真正的节名称（不含 values 类别）
     */
    fun getRealSectionNames(): Set<String> = realSectionNames

    fun getAllValueCategoryNames(): Set<String> = valueCategories.keys
    fun getValueCategory(name: String): ValueCategory? = valueCategories[name]
    fun isRealSection(name: String): Boolean = name in realSectionNames
}
