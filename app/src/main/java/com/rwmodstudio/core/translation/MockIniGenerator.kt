package com.rwmodstudio.core.translation

import android.content.Context
import com.rwmodstudio.core.RwmodPaths
import com.rwmodstudio.core.VerifyManager

/**
 * 属性分类器：把属性归类到补全类型分类（分类页/模拟 ini 共用）。
 * 唯一来源是「真实补全判定」——由 feature 层按 Provider 实际服务归属构造实现
 * （与自动补全同一组 Provider、同顺序，首中即定），不存在第二份名单。
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
 * 分类源唯一：真实补全判定（与自动补全同源，永不漂移）。
 */
object MockIniGenerator {

    /**
     * 生成并写盘模拟 ini 文档。
     * 验证码不匹配或文件不存在时重新生成。
     * @param classifier 真实补全分类器（必填，与自动补全同源）
     * @param force 强制重新生成（忽略验证码检查）
     * @return 生成的文档文件；失败返回 null
     */
    suspend fun generate(context: Context, classifier: PropertyClassifier, force: Boolean = false): java.io.File? {
        return try {
            val file = RwmodPaths.mockIniFile
            val isValid = VerifyManager.isValid(VerifyManager.MOCK_INI, VerifyManager.MOCK_INI_CODE)
            if (!force && isValid && file.exists()) {
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

    /**
     * 构建模拟 ini 文档文本：按补全类型分类，每类一节，列出该分类属性的英文键。
     * @param classifier 真实补全分类器（与自动补全同源）
     */
    suspend fun buildDoc(context: Context, classifier: PropertyClassifier): String {
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

        // 分类器：真实补全判定（按 Provider 实际服务归属，与自动补全同源）
        fun classifyProp(p: CodeReferenceRepository.PropertyInfo): String =
            classifier.classify(p.name, p.name_en)

        // 分类名 → 属性英文键列表（有序）
        val categoryKeys = linkedMapOf<String, MutableList<String>>()
        fun add(cat: String, nameEn: String) {
            categoryKeys.getOrPut(cat) { mutableListOf() }.add(nameEn)
        }
        // 输出键：优先英文键(name_en)；指令类属性(如 @memory) name 即英文且无 name_en，回退用 name
        fun outKey(p: CodeReferenceRepository.PropertyInfo) = p.name_en?.takeIf { it.isNotBlank() } ?: p.name
        props.values.forEach { p -> add(classifyProp(p), outKey(p)) }
        synthetic.forEach { add(classifyProp(it), outKey(it)) }

        val sb = StringBuilder()
        sb.append("; 模拟 ini 文档：全部属性键按补全类型分类\n")
        sb.append("; 分类源：真实补全判定（按 Provider 实际服务归属，与自动补全同源）\n")
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
