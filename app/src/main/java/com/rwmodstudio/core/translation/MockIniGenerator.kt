package com.rwmodstudio.core.translation

import android.content.Context
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.core.VerifyManager

/**
 * 属性分类器：把属性归类到补全类型分类（分类页/模拟 ini 共用）。
 * 硬编码名单模式用 [CompletionTypeRepository.classify]；真实补全模式由 feature 层
 * 按 Provider 实际服务归属构造实现（两者可随时切换，模拟 ini 跟随当前模式生成）。
 */
fun interface PropertyClassifier {
    fun classify(name: String, nameEn: String?): String
}

/**
 * 模拟 ini 文档生成器（开发者工具「模拟ini」）。
 *
 * 将代码参考库全部属性按「补全类型分类」分组，生成一份真实 ini 文档：
 * 每个分类一个 [节]，节内一行一个英文属性键（编辑器打开后自动中文化显示）。
 * 文档写入 RWmod/cache/mock_ini.ini，配验证码控制强刷。
 *
 * 分类源两种模式（由调用方传入 [classifier]）：
 * - null：硬编码名单（CompletionTypeRepository.classify，默认）；
 * - 非 null：真实补全判定（分类页/开发者页按持久化模式传入）。
 * 文件首行写入模式标记；模式切换时强制重新生成，避免旧模式缓存残留。
 */
object MockIniGenerator {

    /** 硬编码名单模式的文档首行标记 */
    private const val MODE_HARDCODED = "hardcoded"
    /** 真实补全判定模式的文档首行标记 */
    private const val MODE_REAL = "real"

    /**
     * 生成并写盘模拟 ini 文档。
     * 验证码不匹配、文件不存在或**当前模式与已生成文件不一致**时重新生成。
     * @param classifier 真实补全分类器；null 表示用硬编码名单
     * @param force 强制重新生成（忽略验证码与模式匹配检查）
     * @return 生成的文档文件；失败返回 null
     */
    suspend fun generate(context: Context, classifier: PropertyClassifier? = null, force: Boolean = false): java.io.File? {
        return try {
            val file = RwmodPaths.mockIniFile
            val isValid = VerifyManager.isValid(VerifyManager.MOCK_INI, VerifyManager.MOCK_INI_CODE)
            val mode = if (classifier != null) MODE_REAL else MODE_HARDCODED
            if (!force && isValid && file.exists() && fileMode(file) == mode) {
                return file
            }
            val text = buildDoc(context, classifier)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            VerifyManager.write(VerifyManager.MOCK_INI, VerifyManager.MOCK_INI_CODE)
            file
        } catch (e: Exception) {
            android.util.Log.e("MockIniGenerator", "Failed to generate mock ini", e)
            null
        }
    }

    /** 读取已生成文件头几行的模式标记；文件不存在或标记缺失返回硬编码标记（旧文件视为硬编码模式） */
    private fun fileMode(file: java.io.File): String {
        return try {
            val head = file.bufferedReader().use { reader ->
                buildString {
                    repeat(3) { append(reader.readLine() ?: "").append('\n') }
                }
            }
            if (head.contains("真实补全")) MODE_REAL else MODE_HARDCODED
        } catch (e: Exception) {
            MODE_HARDCODED
        }
    }

    /**
     * 构建模拟 ini 文档文本：按补全类型分类，每类一节，列出该分类属性的英文键。
     * @param classifier 真实补全分类器；null 用硬编码名单 [CompletionTypeRepository.classify]
     */
    suspend fun buildDoc(context: Context, classifier: PropertyClassifier? = null): String {
        val repo = CodeReferenceRepository()
        repo.loadFromAssets(context)
        CompletionTypeRepository.load()

        // 收集 17 个真实节的属性（按 name 去重，首中优先；与补全类型分类页同口径）
        val props = linkedMapOf<String, CodeReferenceRepository.PropertyInfo>()
        repo.getRealSectionNames().forEach { sec ->
            repo.getPropertiesForSection(sec).forEach { p ->
                if (!p.name.contains(": ")) props.putIfAbsent(p.name, p)
            }
        }
        // 合成条目（不在 code_reference 但真实存在的指令属性）
        val synthetic = CompletionTypeRepository.SYNTHETIC_PROPERTIES.map {
            CodeReferenceRepository.PropertyInfo(name = it.name, name_en = it.nameEn)
        }

        // 分类器：真实补全模式用传入的 classifier，否则硬编码名单
        fun classifyProp(p: CodeReferenceRepository.PropertyInfo): String {
            val c = classifier
            return if (c != null) c.classify(p.name, p.name_en) else CompletionTypeRepository.classify(p.name, p.name_en)
        }

        // 分类名 → 属性英文键列表（有序）
        val categoryKeys = linkedMapOf<String, MutableList<String>>()
        fun add(cat: String, nameEn: String) {
            categoryKeys.getOrPut(cat) { mutableListOf() }.add(nameEn)
        }
        // 输出键：优先英文键(name_en)；指令类属性(如 @memory) name 即英文且无 name_en，回退用 name
        fun outKey(p: CodeReferenceRepository.PropertyInfo) = p.name_en?.takeIf { it.isNotBlank() } ?: p.name
        props.values.forEach { p -> add(classifyProp(p), outKey(p)) }
        synthetic.forEach { add(classifyProp(it), outKey(it)) }

        val isReal = classifier != null
        val sb = StringBuilder()
        sb.append("; 模拟 ini 文档：全部属性键按补全类型分类\n")
        sb.append(
            if (isReal) "; 分类源：真实补全判定（按 Provider 实际服务归属，与自动补全同源）\n"
            else "; 分类源：硬编码补全类型名单（编辑器打开后键名自动中文化显示）\n"
        )
        sb.append('\n')
        val order = CompletionTypeRepository.CATEGORY_ORDER.filter { it in categoryKeys }
        order.forEach { cat ->
            val keys = categoryKeys[cat].orEmpty().filter { it.isNotBlank() }.distinct().sorted()
            sb.append("[$cat]\n")
            keys.forEach { sb.append("$it:\n") }
            sb.append('\n')
        }
        return sb.toString()
    }
}
