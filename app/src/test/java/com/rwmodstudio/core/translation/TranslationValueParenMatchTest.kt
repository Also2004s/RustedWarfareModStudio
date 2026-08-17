package com.rwmodstudio.core.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 值翻译括号匹配：self.XXX()/XXX() 归一化回退。
 * 顺序：精确 → self.XXX → 裸 XXX；直接返回翻译库样式（裸名），不拼回 self./()。
 */
class TranslationValueParenMatchTest {

    private fun loadDictFromAssets(): TranslationDict {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/translation.txt"),
            File(System.getProperty("user.dir"), "src/main/assets/data/translation.txt"),
            File("app/src/main/assets/data/translation.txt"),
            File("src/main/assets/data/translation.txt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("translation.txt not found. user.dir=${System.getProperty("user.dir")}")
        val engine = TranslationEngine.getInstance()
        val dict = engine.getTranslationDict()
        val loadMethod = dict::class.java.getDeclaredMethod("loadFromFile", File::class.java)
        loadMethod.isAccessible = true
        loadMethod.invoke(dict, file)
        return dict
    }

    @Test
    fun selfParenPrefersSelfForm() {
        val dict = loadDictFromAssets()
        // 翻译库有 self.hasFlag / self.hp / self.ammo，优先命中 self. 格式
        assertEquals("自身有标志", dict.getValueTranslation("self.hasFlag()"))
        assertEquals("自身血量", dict.getValueTranslation("self.hp()"))
        assertEquals("自身弹药", dict.getValueTranslation("self.ammo()"))
    }

    @Test
    fun selfParenFallsBackToBareForm() {
        val dict = loadDictFromAssets()
        // 翻译库无 self. 格式，回退裸 XXX 格式
        assertEquals("敌人有此单位数量", dict.getValueTranslation("self.numberOfUnitsInEnemyTeam()"))
        assertEquals("读取单位内存", dict.getValueTranslation("self.readUnitMemory()"))
        assertEquals("有活动的路径点", dict.getValueTranslation("self.hasActiveWaypoint()"))
        assertEquals("全图中此单位数量", dict.getValueTranslation("self.numberOfUnitsInAllTeams()"))
    }

    @Test
    fun bareParenFormMatchesBareKey() {
        val dict = loadDictFromAssets()
        assertEquals("全图中此单位数量", dict.getValueTranslation("numberOfUnitsInAllTeams()"))
        assertEquals("接近单位", dict.getValueTranslation("nearestUnit()"))
    }

    @Test
    fun backTranslationReturnsLibraryStyle() {
        val dict = loadDictFromAssets()
        // 反向同样剥 self./()，直接返回库样式（裸英文），不拼回
        assertEquals("numberOfUnitsInEnemyTeam", dict.getValueTranslationBack("self.敌人有此单位数量()"))
        assertEquals("self.ammo", dict.getValueTranslationBack("自身弹药()"))
        assertEquals("hasActiveWaypoint", dict.getValueTranslationBack("有活动的路径点()"))
    }

    @Test
    fun nonEmptyParensNotStripped() {
        val dict = loadDictFromAssets()
        // 带参括号 (withActionTag="#") 不剥离
        assertEquals("queueItemAdded(withActionTag=\"#\")", dict.getValueTranslation("queueItemAdded(withActionTag=\"#\")"))
    }

    @Test
    fun unmatchedReturnsOriginal() {
        val dict = loadDictFromAssets()
        assertEquals("self.customUnknownFn()", dict.getValueTranslation("self.customUnknownFn()"))
        assertEquals("someValue", dict.getValueTranslation("someValue"))
        assertEquals("未知函数()", dict.getValueTranslationBack("未知函数()"))
    }

    @Test
    fun ziShenZiYuanDictFacts() {
        val dict = loadDictFromAssets()
        // 自身资源 应在中文 key 集合中（供翻译库兜底）
        assertTrue("自身资源" in dict.getAllChineseKeys(), "自身资源 应在 zhToEn keys 中")
        // self.resource() 应翻译为 自身资源（值补全 label）
        assertEquals("自身资源", dict.getValueTranslation("self.resource()"))
    }
}