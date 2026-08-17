package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.translation.TranslationDict
import com.rwmodstudio.core.translation.TranslationEngine
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 函数参数补全纯函数单测：函数 key 归一化、当前参数解析、已用参数去重。
 * 不依赖 Android（[ValueCompletionRequest] 由 CompletionProvider 构造，这里只测解析辅助函数）。
 */
class FunctionParameterCompletionTest {

    // ===== resolveFunctionBaseKey：函数基名归一化 =====

    @Test
    fun resolveEnglishFunctionKey() {
        assertEquals(
            "numberofunitsinenemyteam",
            resolveFunctionBaseKey("requireConditional: if self.numberOfUnitsInEnemyTeam(") { it }
        )
        assertEquals(
            "numberofunitsinenemyteam",
            resolveFunctionBaseKey("requireConditional: if numberOfUnitsInEnemyTeam(") { it }
        )
    }

    @Test
    fun resolveChineseFunctionKeyViaTranslation() {
        assertEquals(
            "numberofunitsinenemyteam",
            resolveFunctionBaseKey("requireConditional: if 敌人有此单位数量(") { "numberOfUnitsInEnemyTeam" }
        )
        // 链式前缀：父单位.读取单位内存 → readUnitMemory
        assertEquals(
            "readunitmemory",
            resolveFunctionBaseKey("requireConditional: if 父单位.读取单位内存(") { "readUnitMemory" }
        )
    }

    @Test
    fun resolveMemoryContainsKey() {
        assertEquals(
            "contains",
            resolveFunctionBaseKey("requireConditional: if 内存.savedCoord.contains(") { it }
        )
    }

    @Test
    fun resolveGroupingParenReturnsNull() {
        // 逻辑关键字 if 的分组括号不是函数调用
        assertNull(resolveFunctionBaseKey("requireConditional: if (自身能量>=2 or 自身弹药>=1)") { it })
        // 无键: 前缀不解析（值补全不触发）
        assertNull(resolveFunctionBaseKey("if 敌人有此单位数量(") { it })
        // 括号前无函数名
        assertNull(resolveFunctionBaseKey("requireConditional: (") { it })
    }

    // ===== currentParamKeyInParens：当前参数解析 =====

    @Test
    fun currentParamAfterOpenParenOrCommaIsNull() {
        assertNull(currentParamKeyInParens("requireConditional: if 敌人有此单位数量("))
        assertNull(currentParamKeyInParens("requireConditional: if 敌人有此单位数量(需标签='fish', "))
        assertNull(currentParamKeyInParens("requireConditional: if 敌人有此单位数量(, "))
    }

    @Test
    fun currentParamAfterEq() {
        assertEquals(
            "需标签",
            currentParamKeyInParens("requireConditional: if 敌人有此单位数量(需标签=")
        )
        assertEquals(
            "超过",
            currentParamKeyInParens("requireConditional: if 敌人有此单位数量(需标签='fish', 超过=")
        )
        assertEquals(
            "type",
            currentParamKeyInParens("requireConditional: if 读取单位内存(name='x', type=")
        )
    }

    // ===== usedParamKeysInParens：已用参数去重 =====

    @Test
    fun usedParamsDetected() {
        val used = usedParamKeysInParens(
            "requireConditional: if 敌人有此单位数量(需标签='fish', 超过=",
            listOf("需标签", "超过", "少于", "范围内", "包含未完成的", "包含队列中的")
        )
        assertTrue("需标签" in used, "需标签 应判定为已使用")
        assertTrue("超过" in used, "超过 应判定为已使用")
        assertTrue("少于" !in used, "少于 未使用")
    }

    @Test
    fun usedParamsNotMatchingInsideOtherWords() {
        val used = usedParamKeysInParens(
            "spawnUnits: tank(offsetX=10, ",
            listOf("x", "offsetX")
        )
        // offsetX 是段首，x 不应命中 offsetX 内部
        assertTrue("offsetX" in used)
        assertTrue("x" !in used)
    }

    // ===== 二次增强：新增函数基名解析 =====

    @Test
    fun resolveNewFunctionKeys() {
        assertEquals("createmarker", resolveFunctionBaseKey("requireConditional: 创建标记(") { "createMarker" })
        assertEquals("createmarker", resolveFunctionBaseKey("requireConditional: createMarker(") { it })
        assertEquals("getoffsetabsolute", resolveFunctionBaseKey("requireConditional: 获取绝对偏移(") { "getOffsetAbsolute" })
        assertEquals("numberofqueuedwaypoints", resolveFunctionBaseKey("requireConditional: self.numberOfQueuedWaypoints(") { it })
    }

    // ===== logicnumber 类型判定 =====

    @Test
    fun logicNumberTypeMatches() {
        assertTrue(isLogicNumberValueType("logicnumber"))
        assertTrue(isLogicNumberValueType("logicNumber"))
        assertTrue(isLogicNumberValueType("logic"))
        assertTrue(isLogicNumberValueType(" LogicNumber "))
        assertFalse(isLogicNumberValueType("logicboolean"))
        assertFalse(isLogicNumberValueType("bool"))
    }

    // ===== defineUnitMemory 类型位置判定 =====

    @Test
    fun memoryTypePositionDetection() {
        assertTrue(isMemoryTypePosition(""), "段首为空 → 类型位置")
        assertTrue(isMemoryTypePosition("boolean nukeActive, "), ", 后为空 → 类型位置")
        assertTrue(isMemoryTypePosition("boo"), "正在输类型名（无空格）→ 类型位置")
        assertFalse(isMemoryTypePosition("boolean nukeAc"), "已含空格在输变量名 → 非类型位置")
        assertFalse(isMemoryTypePosition("boolean nukeActive"), "完整类型+变量名 → 非类型位置")
    }

    // ===== 参数值引号包裹 =====

    @Test
    fun quoteTagValuesWithSingleQuote() {
        val items = buildParamValueSuggestions("tag", listOf("fish", "bird"), "")
        assertEquals(listOf("'bird'", "'fish'"), items.map { it.insertText }, "tag 值应按字母序插入单引号包裹")
        assertEquals(listOf("'bird'", "'fish'"), items.map { it.label })
        assertTrue(items.all { it.prefixLength == 0 })
    }

    @Test
    fun quoteTypeRelationEnumValues() {
        assertEquals("'string'", buildParamValueSuggestions("type", listOf("string"), "").single().insertText)
        assertEquals("'中立'", buildParamValueSuggestions("relation", listOf("中立"), "").single().insertText)
        assertEquals("'LAND'", buildParamValueSuggestions("enum", listOf("LAND"), "").single().insertText)
    }

    @Test
    fun noQuoteForResourceAndBoolValues() {
        assertEquals("gold", buildParamValueSuggestions("resource", listOf("gold"), "").single().insertText)
        assertEquals("真", buildParamValueSuggestions("bool", listOf("真"), "").single().insertText)
    }

    @Test
    fun memoryNameQuotedAndSorted() {
        // 读取单位内存/事件数据的 name 参数：内存变量名单引号包裹
        val items = buildParamValueSuggestions("memoryName", listOf("攻击目标", "lastDock"), "")
        assertEquals(listOf("'lastDock'", "'攻击目标'"), items.map { it.insertText }, "memoryName 值单引号包裹、按字母序")
        // 用户输入引号后仍能匹配
        val typed = buildParamValueSuggestions("memoryName", listOf("攻击目标"), "'攻")
        assertEquals("'攻击目标'", typed.single().insertText)
        assertEquals(2, typed.single().prefixLength, "prefixLength 覆盖已输入引号+字符")
    }

    // ===== 本轮新增：条件 getter 参数表 + 位置参数 + 角度偏移 =====

    private fun loadParamFile(name: String): ParamDataLoader.ParamDataFile {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/param/$name.json"),
            File(System.getProperty("user.dir"), "src/main/assets/data/param/$name.json"),
            File("app/src/main/assets/data/param/$name.json"),
            File("src/main/assets/data/param/$name.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("param/$name.json not found. user.dir=${System.getProperty("user.dir")}")
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString<ParamDataLoader.ParamDataFile>(file.readText())
    }

    @Test
    fun conditionalGetterParamTablesPresent() {
        // 真实 MOD 高频条件 getter：自身血量/高度/自身弹药/护盾/自身杀敌数/自身队列量/自身运输数量/自身有子单位/自身有父单位
        val data = loadParamFile("logicboolean")
        val keys = data.functions.map { it.key }
        for (k in listOf("hp", "height", "ammo", "shield", "kills", "queueSize", "transportingCount", "numberOfAttachedUnits", "hasParent")) {
            assertTrue(k in keys, "$k 应已建参数表")
        }
        val hp = data.functions.first { it.key == "hp" }
        assertEquals(listOf("超过", "少于", "等于", "空的", "满"), hp.params.map { it.zh }, "自身血量参数中文名")
        val queue = data.functions.first { it.key == "queueSize" }
        assertEquals("少于", queue.params.first { it.key == "lessThan" }.zh)
        val attached = data.functions.first { it.key == "numberOfAttachedUnits" }
        assertEquals("tag", attached.params.first { it.key == "withTag" }.type, "自身有子单位 需标签 为标签参数")
        val parent = data.functions.first { it.key == "hasParent" }
        assertEquals("需标签", parent.params.first { it.key == "withTag" }.zh, "自身有父单位 需标签")
    }

    @Test
    fun readUnitMemoryNameIsPositionalMemoryName() {
        val data = loadParamFile("logicboolean")
        for (k in listOf("readUnitMemory", "eventData")) {
            val fn = data.functions.first { it.key == k }
            assertEquals("memoryName", fn.params.first().type, "$k 第一个参数 name 应为 memoryName（位置参数，不弹 name=）")
        }
    }

    @Test
    fun getOffsetRelativeDirOffsetIsChinese() {
        val data = loadParamFile("logicboolean")
        val fn = data.functions.first { it.key == "getOffsetRelative" }
        assertEquals("角度偏移", fn.params.first { it.key == "dirOffset" }.zh, "获取相对偏移 角度偏移 参数中文名（真实 MOD 写法）")
    }

    // ===== 第三轮：actionTag 动作标签 + 互斥标注 + queueSize withActionTag =====

    @Test
    fun actionTagValuesQuotedAndSorted() {
        // 队列项目添加/取消 withActionTag：行动/隐藏行动 节内 tags 值，单引号包裹、按字母序
        val items = buildParamValueSuggestions("actionTag", listOf("blink", "升级机枪"), "")
        assertEquals(listOf("'blink'", "'升级机枪'"), items.map { it.insertText }, "actionTag 值单引号包裹、按字母序")
        val typed = buildParamValueSuggestions("actionTag", listOf("blink"), "'b")
        assertEquals("'blink'", typed.single().insertText, "输入引号前缀也能命中")
    }

    @Test
    fun autoTriggerWithActionTagUsesActionTagType() {
        val data = loadParamFile("autoTriggerOnEvent")
        for (k in listOf("queueItemAdded", "queueItemCancelled")) {
            val fn = data.functions.first { it.key == k }
            assertEquals("actionTag", fn.params.first { it.key == "withActionTag" }.type, "$k.withActionTag 应为 actionTag（动作节名，非单位标签）")
        }
        // tookDamage 保持单位标签；newMessage 保持消息标签
        assertEquals("tag", data.functions.first { it.key == "tookDamage" }.params.first().type)
        assertEquals("messageTag", data.functions.first { it.key == "newMessage" }.params.first().type)
    }

    @Test
    fun queueSizeHasWithActionTag() {
        val data = loadParamFile("logicboolean")
        val qs = data.functions.first { it.key == "queueSize" }
        val w = qs.params.first { it.key == "withActionTag" }
        assertEquals("actionTag", w.type, "自身队列量 withActionTag（真实 MOD 使用 自身队列量(withActionTag='blink')）")
    }

    @Test
    fun mutuallyExclusiveParamsDetected() {
        assertTrue(isMutuallyExclusiveParam("greaterThan"))
        assertTrue(isMutuallyExclusiveParam("lessThan"))
        assertTrue(isMutuallyExclusiveParam("equalTo"))
        assertTrue(isMutuallyExclusiveParam("empty"))
        assertTrue(isMutuallyExclusiveParam("full"))
        assertFalse(isMutuallyExclusiveParam("withTag"), "需标签 不是互斥条件参数")
        assertFalse(isMutuallyExclusiveParam("withActionTag"))
        assertFalse(isMutuallyExclusiveParam("withinRange"), "范围内 不是互斥条件参数（可与其他参数组合）")
        assertFalse(isMutuallyExclusiveParam(""))
    }

    @Test
    fun quoteMatchingIgnoresUserTypedQuotes() {
        val single = buildParamValueSuggestions("tag", listOf("fish", "bird"), "'fi")
        assertEquals("'fish'", single.single().insertText)
        assertEquals(3, single.single().prefixLength, "prefixLength 应覆盖已输入的引号片段")

        val double = buildParamValueSuggestions("tag", listOf("fish"), "\"fi")
        assertEquals("'fish'", double.single().insertText, "用户输入双引号也命中并统一补单引号")
    }

    // ===== isKnownParamFunctionContext：括号内已知参数函数判定（null context 只测纯解析分支） =====

    @Test
    fun knownParamFunctionContextRequiresParens() {
        assertFalse(isKnownParamFunctionContext("requireConditional: if numberOfUnitsInEnemyTeam", null, null))
        assertFalse(isKnownParamFunctionContext("requireConditional: if 敌人有此单位数量", null, null))
    }

    @Test
    fun knownParamFunctionHasResourcesSpecialCaseWithoutContext() {
        // hasResources 特例不依赖参数表加载
        assertTrue(isKnownParamFunctionContext("requireConditional: if hasResources(", null, null))
    }

    @Test
    fun knownParamFunctionNullContextCannotResolveTable() {
        // context 为 null 时无法加载参数表，除 hasResources 外均返回 false
        assertFalse(isKnownParamFunctionContext("requireConditional: if numberOfUnitsInEnemyTeam(", null, null))
        assertFalse(isKnownParamFunctionContext("requireConditional: if 敌人有此单位数量(", null, null))
        assertFalse(isKnownParamFunctionContext("requireConditional: if rnd(", null, null))
    }

    // ===== 全角逗号 ， 分段去重 =====

    @Test
    fun usedParamsDetectedAfterFullWidthComma() {
        val used = usedParamKeysInParens(
            "requireConditional: if 敌人有此单位数量(需标签='fish'，",
            listOf("需标签", "超过", "少于", "范围内", "包含未完成的", "包含队列中的")
        )
        assertTrue("需标签" in used, "全角逗号后 需标签 应判为已使用")
        assertTrue("超过" !in used, "超过 未使用")
    }

    @Test
    fun usedParamsDetectedFullWidthThenHalfWidth() {
        val used = usedParamKeysInParens(
            "requireConditional: if 敌人有此单位数量(需标签='fish'，超过=5,",
            listOf("需标签", "超过", "少于")
        )
        assertTrue("需标签" in used)
        assertTrue("超过" in used, "半角逗号后 超过 应判为已使用")
        assertTrue("少于" !in used)
    }

    // ===== paramValueAfterEq：= 后原始片段（含空格） =====

    @Test
    fun paramValueAfterEqReturnsRawFragment() {
        assertEquals(" 'fi", paramValueAfterEq("requireConditional: if 敌人有此单位数量(需标签= 'fi"), "带空格保留原始片段")
        assertEquals(4, paramValueAfterEq("requireConditional: if 敌人有此单位数量(需标签= 'fi")!!.length)
        assertEquals("'fi", paramValueAfterEq("requireConditional: if 敌人有此单位数量(需标签='fi"), "无空格")
        assertEquals(3, paramValueAfterEq("requireConditional: if 敌人有此单位数量(需标签='fi")!!.length)
        assertEquals("", paramValueAfterEq("requireConditional: if 敌人有此单位数量(需标签="), "= 后为空")
        assertNull(paramValueAfterEq("requireConditional: if 敌人有此单位数量("), "无 = 返回 null")
        assertNull(paramValueAfterEq("敌人有此单位数量(需标签="), "无 键: 前缀返回 null")
    }

    @Test
    fun paramValueAfterEqWithColonInsideValue() {
        // 值内冒号（ROOT:）不应破坏 = 后片段解析
        assertEquals("st", paramValueAfterEq("需要条件: 读取单位内存(name='ROOT:x', type=st"))
        assertEquals("", paramValueAfterEq("需要条件: 读取单位内存(name='ROOT:x', type="))
    }

    // ===== 值内冒号不再破坏函数基名解析 =====

    @Test
    fun resolveFunctionBaseKeyWithColonInsideValue() {
        assertEquals(
            "readunitmemory",
            resolveFunctionBaseKey("需要条件: 读取单位内存(name='ROOT:x', type=") { "readUnitMemory" }
        )
        assertEquals(
            "numberofunitsinenemyteam",
            resolveFunctionBaseKey("requireConditional: if 敌人有此单位数量(需标签='ROOT:path', 超过=") { "numberOfUnitsInEnemyTeam" }
        )
    }

    @Test
    fun usedParamsDetectedWithColonInsideValue() {
        val used = usedParamKeysInParens(
            "requireConditional: if 敌人有此单位数量(需标签='ROOT:path', 超过=",
            listOf("需标签", "超过", "少于")
        )
        assertTrue("需标签" in used)
        assertTrue("超过" in used, "值内冒号不影响后续参数判已用")
        assertTrue("少于" !in used)
    }

    // ===== buildParamValueSuggestions：显式 prefixLength 与排序 =====

    @Test
    fun buildParamValueSuggestionsHonorsExplicitPrefixLength() {
        val items = buildParamValueSuggestions("tag", listOf("fish", "bird"), "'fi", prefixLength = 4)
        assertEquals("'fish'", items.single().insertText)
        assertEquals(4, items.single().prefixLength, "显式 prefixLength（含空格整体替换）优先")
    }

    @Test
    fun relationValuesKeepAuthoredOrder() {
        val items = buildParamValueSuggestions("relation", listOf("所有", "盟友", "敌方", "中立"), "")
        assertEquals(listOf("'所有'", "'盟友'", "'敌方'", "'中立'"), items.map { it.label }, "relation 保持参数表书写顺序")
    }

    @Test
    fun tagValuesStillSorted() {
        val items = buildParamValueSuggestions("tag", listOf("fish", "bird"), "")
        assertEquals(listOf("'bird'", "'fish'"), items.map { it.label }, "tag 仍按字母排序")
    }

    // ===== 附件(slot='附属节名')：attachmentSlot 参数类型 =====

    @Test
    fun attachmentSlotValuesQuotedAndSorted() {
        val items = buildParamValueSuggestions("attachmentSlot", listOf("炮塔座", "座"), "")
        assertEquals(listOf("'座'", "'炮塔座'"), items.map { it.label }, "attachmentSlot 单引号包裹且按字母排序")
        assertTrue(items.all { it.prefixLength == 0 })
    }

    @Test
    fun attachmentSlotMatchesUserTypedPrefix() {
        val items = buildParamValueSuggestions("attachmentSlot", listOf("炮塔座", "座"), "'炮")
        assertEquals("'炮塔座'", items.single().insertText)
        assertEquals(2, items.single().prefixLength, "已输入引号+前缀应整体替换")
    }

    @Test
    fun paramTableSlotTypes() {
        // 来源表：attachment.slot 为 attachmentSlot（引号附属节名），transporting.slot 保持 number
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/param/logicboolean.json"),
            File(System.getProperty("user.dir"), "src/main/assets/data/param/logicboolean.json"),
            File("app/src/main/assets/data/param/logicboolean.json"),
            File("src/main/assets/data/param/logicboolean.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("param/logicboolean.json not found. user.dir=${System.getProperty("user.dir")}")
        val json = Json { ignoreUnknownKeys = true }
        val data = json.decodeFromString<ParamDataLoader.ParamDataFile>(file.readText())
        val attachment = data.functions.first { it.key == "attachment" }
        assertEquals("attachmentSlot", attachment.params.first { it.key == "slot" }.type, "attachment.slot 应为 attachmentSlot")
        val transporting = data.functions.first { it.key == "transporting" }
        assertEquals("number", transporting.params.first { it.key == "slot" }.type, "transporting.slot 保持 number")
    }

    // ===== 消息标签（新消息(需标签=)）：messageTag 参数类型 =====

    @Test
    fun messageTagValuesQuotedAndSorted() {
        val items = buildParamValueSuggestions("messageTag", listOf("hitZone", "目标匹配"), "")
        assertEquals(listOf("'hitZone'", "'目标匹配'"), items.map { it.insertText }, "messageTag 应按字母序插入单引号包裹")
        assertEquals(listOf("'hitZone'", "'目标匹配'"), items.map { it.label })
        assertTrue(items.all { it.prefixLength == 0 })
    }

    @Test
    fun messageTagMatchesUserTypedPrefix() {
        val items = buildParamValueSuggestions("messageTag", listOf("hitZone", "目标匹配"), "'hi")
        assertEquals("'hitZone'", items.single().insertText)
        assertEquals(3, items.single().prefixLength, "已输入引号+前缀应整体替换")
    }

    @Test
    fun newMessageParamTableUsesMessageTagType() {
        // 只有 新消息 的 withTag 是消息标签；tookDamage 等保持单位标签语义
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/param/autoTriggerOnEvent.json"),
            File(System.getProperty("user.dir"), "src/main/assets/data/param/autoTriggerOnEvent.json"),
            File("app/src/main/assets/data/param/autoTriggerOnEvent.json"),
            File("src/main/assets/data/param/autoTriggerOnEvent.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("param/autoTriggerOnEvent.json not found. user.dir=${System.getProperty("user.dir")}")
        val json = Json { ignoreUnknownKeys = true }
        val data = json.decodeFromString<ParamDataLoader.ParamDataFile>(file.readText())
        val newMessage = data.functions.first { it.key == "newMessage" }
        assertEquals("messageTag", newMessage.params.first { it.key == "withTag" }.type, "newMessage.withTag 应为 messageTag")
        val tookDamage = data.functions.first { it.key == "tookDamage" }
        assertEquals("tag", tookDamage.params.first { it.key == "withTag" }.type, "tookDamage.withTag 保持 tag（单位标签）")
    }

    // ===== 关系参数：完整 7 值英文 + 字典翻译 =====

    @Test
    fun relationParamTableHasSevenEnglishValues() {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "app/src/main/assets/data/param/logicboolean.json"),
            File(System.getProperty("user.dir"), "src/main/assets/data/param/logicboolean.json"),
            File("app/src/main/assets/data/param/logicboolean.json"),
            File("src/main/assets/data/param/logicboolean.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("param/logicboolean.json not found. user.dir=${System.getProperty("user.dir")}")
        val json = Json { ignoreUnknownKeys = true }
        val data = json.decodeFromString<ParamDataLoader.ParamDataFile>(file.readText())
        for (fnKey in listOf("nearestUnit", "globalSearchForFirstUnit")) {
            val fn = data.functions.first { it.key == fnKey }
            val relation = fn.params.first { it.key == "relation" }
            assertEquals(
                listOf("own", "notOwn", "neutral", "allyNotOwn", "ally", "enemy", "any"),
                relation.values,
                "$fnKey 关系值应为完整 7 个英文枚举"
            )
        }
    }

    private fun loadDictFromAssets(): TranslationDict {
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

    @Test
    fun translateDictValuesUsesDictHitElseKeepEnglish() {
        val dict = loadDictFromAssets()
        // 字典命中 → 中文（move=移动, own=己方）
        assertEquals(listOf("移动", "己方"), translateDictValues(listOf("move", "own"), dict, true))
        // 字典未命中 → 保持英文原样（notOwn 无翻译条目）
        assertEquals(listOf("notOwn"), translateDictValues(listOf("notOwn"), dict, true))
        // translate=false 或字典为空 → 原样
        assertEquals(listOf("move", "notOwn"), translateDictValues(listOf("move", "notOwn"), dict, false))
        assertEquals(listOf("move", "notOwn"), translateDictValues(listOf("move", "notOwn"), null, true))
    }

    @Test
    fun enumParamSuggestionQuotesTranslatedChinese() {
        // 有活动的路径点(type= 补 '移动'（字典命中），loadInto 保持英文
        val items = buildParamValueSuggestions("enum", listOf("移动", "loadInto"), "")
        assertEquals(listOf("'移动'", "'loadInto'"), items.map { it.insertText }, "enum 值单引号包裹、未命中保持英文")
    }

}
