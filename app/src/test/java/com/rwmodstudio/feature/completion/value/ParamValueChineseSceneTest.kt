package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.core.translation.TranslationEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 括号内（句中）参数值能否用中文：补全词的语言必须跟翻译引擎的实际行为一致。
 *
 * 场景依据（2026-09-17 核实）：
 * - 引擎对 value 内部只做 enToZh/zhToEn 逐词翻译（TranslationDict.compilePatterns 明确不含 valueTranslations）；
 * - LAND/WATER/AIR/HOVER/OVER_CLIFF/OVER_CLIFF_WATER/NONE/AUTO 装载时进 valueTranslations，只在
 *   「整个 value 恰好是该值」时翻译（`运动类型: LAND` → 陆地）；
 * - 于是这批词写在括号里：引擎不翻（`type=LAND` 在中文视图里仍是 LAND），保存反译也认不回
 *   （`type=陆地` 反译不回来）→ 补全必须保持英文。
 *
 * 值列表取自 assets/data/param/logicboolean.json 的真实参数定义，字典取自 assets/data/translation.txt。
 */
class ParamValueChineseSceneTest {

    private fun loadDict(): TranslationDict {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/translation.txt"),
            File(System.getProperty("user.dir"), "src/main/assets/data/translation.txt"),
            File("app/src/main/assets/data/translation.txt"),
            File("src/main/assets/data/translation.txt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("translation.txt not found. user.dir=${System.getProperty("user.dir")}")
        val dict = TranslationEngine.getInstance().getTranslationDict()
        val loadMethod = dict::class.java.getDeclaredMethod("loadFromFile", File::class.java)
        loadMethod.isAccessible = true
        loadMethod.invoke(dict, file)
        return dict
    }

    /** 自身在地块类型上(type=)：整值专用词（LAND/AIR/WATER/HOVER/NONE/OVER_CLIFF*）→ 括号内整批英文 */
    @Test
    fun tileTypeValuesStayEnglishInParens() {
        val dict = loadDict()
        val values = listOf(
            "NONE", "LAND", "BUILDING", "AIR", "WATER", "HOVER", "OVER_CLIFF", "OVER_CLIFF_WATER"
        )
        assertFalse(
            canTranslateValuesInPhrase(values, dict),
            "地块类型含 LAND 等整值专用词：括号内引擎翻不动、反译也认不回，必须整批保持英文"
        )
    }

    /** 队列路径点行动类型（move/attackMove/guard…）：普通词条，句中双向可译 → 继续用中文 */
    @Test
    fun waypointActionTypeValuesKeepChinese() {
        val dict = loadDict()
        val values = listOf(
            "move", "attackMove", "guard", "loadInto", "loadUp", "attack",
            "reclaim", "repair", "touchTarget", "build", "follow", "setPassiveTarget"
        )
        assertTrue(canTranslateValuesInPhrase(values, dict), "路径点行动类型是普通词条，句中可反译，应继续用中文")
    }

    /** 阵营/关系（own/ally/enemy…）：普通词条 → 继续用中文 */
    @Test
    fun teamRelationValuesKeepChinese() {
        val dict = loadDict()
        val values = listOf("own", "notOwn", "neutral", "allyNotOwn", "ally", "enemy", "any")
        assertTrue(canTranslateValuesInPhrase(values, dict), "阵营关系是普通词条，句中可反译，应继续用中文")
    }

    /** BUILDING 未被翻译库收录（库里是小写 building）→ 保持英文，且不该让它把整批判定带偏 */
    @Test
    fun buildingIsNotSpecialValueButIsUntranslated() {
        val dict = loadDict()
        assertFalse("BUILDING" in dict.getSpecialValues(), "BUILDING 不是整值专用词")
        assertTrue(
            canTranslateValuesInPhrase(listOf("BUILDING"), dict),
            "BUILDING 单独看可进中文分支（无中文形式则原样英文），整批判定由 LAND 等词决定"
        )
    }

    // ===== 闸2：屏蔽词开关「引号内的词不翻译」（默认关）=====

    /** 开关关着（默认）：引号内的中文照样能被反译回英文 → 普通词条继续用中文 */
    @Test
    fun quotedValuesKeepChineseWhenProtectionOff() {
        val dict = loadDict()
        val values = listOf("move", "attackMove", "guard")
        assertTrue(
            canTranslateValuesInPhrase(values, dict, quoted = true, quotedProtected = false),
            "引号保护关着时，引号内的普通词条可双向翻译，继续用中文"
        )
        assertTrue(
            canTranslateValuesInPhrase(listOf("own", "ally"), dict, quoted = true, quotedProtected = false),
            "阵营关系同理"
        )
    }

    /** 开关打开：引号内被整段保护 → 引号包裹的值必须整批英文（中文会永久留在文件里） */
    @Test
    fun quotedValuesMustBeEnglishWhenProtectionOn() {
        val dict = loadDict()
        val values = listOf("move", "attackMove", "guard")
        assertFalse(
            canTranslateValuesInPhrase(values, dict, quoted = true, quotedProtected = true),
            "引号保护打开时，引号内的中文保存反译不回来，必须整批保持英文"
        )
        assertFalse(
            canTranslateValuesInPhrase(listOf("own", "ally"), dict, quoted = true, quotedProtected = true),
            "阵营关系同理"
        )
    }

    /** 开关打开时，**不带引号**的位置不受影响（引号保护只管引号里的内容） */
    @Test
    fun unquotedValuesUnaffectedByQuotedProtection() {
        val dict = loadDict()
        assertTrue(
            canTranslateValuesInPhrase(listOf("move", "guard"), dict, quoted = false, quotedProtected = true),
            "引号保护只作用于引号内，不带引号的值照样可以用中文"
        )
    }

    /** 参数类型与引号的关系：enum/type/relation 一定会被引号包裹（开关打开时即整批英文） */
    @Test
    fun fixedEnumParamTypesAreQuoted() {
        assertTrue("enum" in quotedValueParamTypes)
        assertTrue("type" in quotedValueParamTypes)
        assertTrue("relation" in quotedValueParamTypes)
        assertFalse("resource" in quotedValueParamTypes, "资源名补全不带引号，不受引号保护影响")
        assertFalse("bool" in quotedValueParamTypes, "布尔值补全不带引号")
    }
}
