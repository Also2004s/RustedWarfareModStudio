package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.shouldReturnValueOnlyForValueFragment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 值补全上下文判定单测：括号内抑制与资源引用前缀。
 */
class ValueCompletionContextTest {

    @Test
    fun isInsideParenthesesDetectsUnclosedOpenParen() {
        assertTrue(isInsideParentheses("name:("), "直接跟在冒号后的 ( 应判定为括号内")
        assertTrue(isInsideParentheses("name: ("), "带空格的 ( 应判定为括号内")
        assertTrue(isInsideParentheses("spawnUnits: tank(朝向左="), "( 后有参数文本应判定为括号内")
        assertTrue(isInsideParentheses("text: Fire: %{自身资源.ammo}("), "冒号在值内时也能识别 (")
    }

    @Test
    fun isInsideParenthesesIgnoresClosedParen() {
        assertFalse(isInsideParentheses("name: ()"), "闭合括号后不应判定为括号内")
        assertFalse(isInsideParentheses("spawnUnits: tank(朝向左=90)"), "括号闭合后不应判定为括号内")
        assertFalse(isInsideParentheses("name: 坦克"), "无括号不应判定为括号内")
        assertFalse(isInsideParentheses("no colon"), "无冒号直接返回 false")
    }

    @Test
    fun resourceReferencePrefixMatchesChinese() {
        assertTrue(isResourceReferencePrefix("自身资源"), "仅关键字")
        assertTrue(isResourceReferencePrefix("自身资源."), "关键字加点")
        assertTrue(isResourceReferencePrefix("自身资源.ammo"), "点后片段")
        assertTrue(isResourceReferencePrefix("资源"), "仅关键字")
        assertTrue(isResourceReferencePrefix("资源."), "关键字加点")
        assertTrue(isResourceReferencePrefix("资源.credits"), "点后片段")
    }

    @Test
    fun resourceReferencePrefixMatchesEnglish() {
        assertTrue(isResourceReferencePrefix("self.resource"))
        assertTrue(isResourceReferencePrefix("self.resource."))
        assertTrue(isResourceReferencePrefix("self.resource.ammo"))
        assertTrue(isResourceReferencePrefix("RESOURCE."), "大小写不敏感")
        assertTrue(isResourceReferencePrefix("resource.credits"))
    }

    @Test
    fun resourceReferencePrefixRejectsLookalikes() {
        assertFalse(isResourceReferencePrefix("自身资源x"), "非点后片段不匹配")
        assertFalse(isResourceReferencePrefix("资源池"), "资源 需单独出现或后跟点")
        assertFalse(isResourceReferencePrefix("%{自身资源."), "%{ 前缀不匹配（与内存一致）")
        assertFalse(isResourceReferencePrefix("credits"), "普通资源名不是前缀")
        assertFalse(isResourceReferencePrefix(""), "空串不匹配")
    }

    // ===== 裸关键字不提供 "." 续写项（isBareResourceKeyword / isBareMemoryKeyword） =====

    @Test
    fun bareResourceKeywordDetected() {
        assertTrue(isBareResourceKeyword("自身资源"))
        assertTrue(isBareResourceKeyword("资源"))
        assertTrue(isBareResourceKeyword("self.resource"))
        assertTrue(isBareResourceKeyword("resource"))
        assertFalse(isBareResourceKeyword("自身资源."), "带点应视为已进入点后片段")
        assertFalse(isBareResourceKeyword("自身资源.gold"))
        assertFalse(isBareResourceKeyword("gold"))
    }

    @Test
    fun bareMemoryKeywordDetected() {
        assertTrue(isBareMemoryKeyword("memory"))
        assertTrue(isBareMemoryKeyword("内存"))
        assertFalse(isBareMemoryKeyword("memory."), "带点应视为已进入点后片段")
        assertFalse(isBareMemoryKeyword("memory.x"))
        assertFalse(isBareMemoryKeyword("mem"))
    }

    // ===== 非特殊前缀 . 全补全（isSpecialDotPrefix / dotFallbackFilter） =====

    @Test
    fun specialDotPrefixesExcluded() {
        assertTrue(isSpecialDotPrefix("自身资源."))
        assertTrue(isSpecialDotPrefix("自身资源.gold"))
        assertTrue(isSpecialDotPrefix("资源."))
        assertTrue(isSpecialDotPrefix("self.resource."))
        assertTrue(isSpecialDotPrefix("resource.gold"))
        assertTrue(isSpecialDotPrefix("内存."))
        assertTrue(isSpecialDotPrefix("memory.x"))
        assertFalse(isSpecialDotPrefix("当前动作目标."))
        assertFalse(isSpecialDotPrefix("攻击中."))
        assertFalse(isSpecialDotPrefix("父单位.x"))
    }

    @Test
    fun specialDotPrefixesMatchSegmentAnywhere() {
        // 段内锚定：链式/%{ 插值内出现 资源./内存. 段即让位给专门 Provider
        assertTrue(isSpecialDotPrefix("%{当前动作目标.资源."), "%{ 内资源链")
        assertTrue(isSpecialDotPrefix("当前动作目标.资源.总战力"), "unit 链 .资源.")
        assertTrue(isSpecialDotPrefix("全局资源."))
        assertTrue(isSpecialDotPrefix("当前动作目标.内存.X."), "unit 链 .内存.")
        assertTrue(isSpecialDotPrefix("内存.攻击目标.资源.总"), "嵌套资源段")
        assertTrue(isSpecialDotPrefix("事件来源.资源.总战力"))
        // 误伤防护：资源 后非点不匹配
        assertFalse(isSpecialDotPrefix("当前动作目标.资源类型."), "资源类型 中 资源 后非点")
        assertFalse(isSpecialDotPrefix("自身资源池."), "资源池 中 资源 后非点")
        assertFalse(isSpecialDotPrefix("攻击中.自定义目标2."))
    }

    @Test
    fun dotFallbackFilterExtractsAfterDot() {
        assertEquals(null, dotFallbackFilter("当前动作目标"), "无点不参与")
        assertEquals(null, dotFallbackFilter("自身资源."), "特殊前缀不参与")
        assertEquals("", dotFallbackFilter("当前动作目标."), "点后为空 = 与空格相同全量")
        assertEquals("自", dotFallbackFilter("当前动作目标.自"))
        assertEquals("当前动作目标", dotFallbackFilter("当前动作目标.当前动作目标"), "链式叠加")
        assertEquals(null, dotFallbackFilter("父单位.内存."), "含 内存. 段为特殊前缀，由内存补全接管（段内锚定）")
        assertEquals("x", dotFallbackFilter("攻击中.x"))
    }

    // ===== 值内冒号：isInsideParentheses 取第一个 : =====

    @Test
    fun isInsideParenthesesWithColonInsideValue() {
        assertFalse(isInsideParentheses("自动触发: tookDamage(withTag='ROOT:')"), "闭合括号后应为 false（值内冒号不影响）")
        assertTrue(isInsideParentheses("自动触发: tookDamage(withTag='ROOT:"), "括号未闭合应为 true（值内冒号不影响）")
        assertTrue(isInsideParentheses("需要条件: 读取单位内存(name='ROOT:x', type=st"), "值内冒号不破坏括号内判定")
    }

    // ===== 带点上下文只显示值补全（shouldReturnValueOnlyForValueFragment） =====

    @Test
    fun valueOnlyEarlyReturnForDottedFragment() {
        // 点后已输入前缀（rawPrefix 非空）但值片段带点 → 仍只返回值补全
        assertTrue(shouldReturnValueOnlyForValueFragment(false, true, true, true), "自身资源.g / 当前动作目标.g 应只显示值补全")
        // 光标紧跟触发符后、值片段非空、有结果 → 只显示值补全（原行为）
        assertTrue(shouldReturnValueOnlyForValueFragment(true, true, true, false))
    }

    @Test
    fun valueOnlyEarlyReturnNotTriggered() {
        // 有输入前缀且值片段不带点 → 走通用兜底（翻译库按前缀参与）
        assertFalse(shouldReturnValueOnlyForValueFragment(false, true, true, false), "自动触发: 自 不走值片段早退")
        // 值片段为空（如 自动触发: ）但有值补全结果（真/假/if）→ 早退只显示值补全，不刷全表
        assertTrue(shouldReturnValueOnlyForValueFragment(true, false, true, false))
        // 值补全无结果 → 不早退
        assertFalse(shouldReturnValueOnlyForValueFragment(true, true, false, true))
    }

    // ===== 资源链前缀（resourceChainRegex）：<链>资源. 泛化规则 =====

    @Test
    fun resourceChainRegexMatchesChains() {
        assertTrue(resourceChainRegex.matches("自身资源."), "裸关键字加点")
        assertTrue(resourceChainRegex.matches("自身资源.总"), "点后片段")
        assertTrue(resourceChainRegex.matches("资源.credits"))
        assertTrue(resourceChainRegex.matches("self.resource.ammo"))
        assertTrue(resourceChainRegex.matches("当前动作目标.资源."), "unit 引用链 .资源.")
        assertTrue(resourceChainRegex.matches("当前动作目标.资源.总战力"), "unit 引用链 .资源. 片段")
        assertTrue(resourceChainRegex.matches("攻击中.资源.总"))
        assertTrue(resourceChainRegex.matches("内存.攻击目标.资源.总战力"), "unit 型内存变量 .资源.")
        assertTrue(resourceChainRegex.matches("%{当前动作目标.资源.总"), "%{ 插值内链")
        assertTrue(resourceChainRegex.matches("RESOURCE."), "大小写不敏感")
    }

    @Test
    fun resourceChainRegexRejectsNonChains() {
        assertFalse(resourceChainRegex.matches("自身资源"), "裸关键字无点不匹配（由属性型/裸关键字分支处理）")
        assertFalse(resourceChainRegex.matches("资源池"))
        assertFalse(resourceChainRegex.matches("credits"))
        assertFalse(resourceChainRegex.matches(""))
    }

    @Test
    fun resourceChainRegexGroups() {
        val m = resourceChainRegex.find("内存.攻击目标.资源.总战力") ?: error("should match")
        assertEquals("内存.攻击目标.资源", m.groupValues[1], "保留链 + 资源 段")
        assertEquals("总战力", m.groupValues[2], "点后已输入片段")
        val m2 = resourceChainRegex.find("当前动作目标.资源.") ?: error("should match")
        assertEquals("当前动作目标.资源", m2.groupValues[1])
        assertEquals("", m2.groupValues[2])
    }

    // ===== 内存链前缀（memoryUnitVarChainRegex）：内存.<变量>. =====

    @Test
    fun memoryUnitVarChainRegexMatchesUnitVarDot() {
        val m = memoryUnitVarChainRegex.find("内存.攻击目标.总") ?: error("should match")
        assertEquals("攻击目标", m.groupValues[1], "内存变量名")
        assertEquals("总", m.groupValues[2], "点后片段")
        val m2 = memoryUnitVarChainRegex.find("memory.attacking.") ?: error("should match")
        assertEquals("attacking", m2.groupValues[1])
        assertEquals("", m2.groupValues[2])
        assertFalse(memoryUnitVarChainRegex.matches("内存.攻击目标"), "无尾点不匹配")
        // 嵌套（内存.变量.资源.总）：变量段取首个点前，剩余链在 group2，资源名由 resourceChainRegex 接管
        val m3 = memoryUnitVarChainRegex.find("内存.攻击目标.资源.总") ?: error("should match")
        assertEquals("攻击目标", m3.groupValues[1])
        assertEquals("资源.总", m3.groupValues[2])
    }

    @Test
    fun memoryUnitVarChainRegexMatchesInterpolation() {
        // 非锚定：%{ 插值内与任意位置出现 内存./memory. 段均匹配（光标在点后片段后，未含闭合 }）
        val m = memoryUnitVarChainRegex.find("%{内存.攻击目标.总战力") ?: error("should match")
        assertEquals("攻击目标", m.groupValues[1])
        assertEquals("总战力", m.groupValues[2])
        val m2 = memoryUnitVarChainRegex.find("%{内存.资源点.hp}") ?: error("should match")
        assertEquals("资源点", m2.groupValues[1])
        assertEquals("hp}", m2.groupValues[2], "光标后内容（含 }）保留在点后片段")
    }

    // ===== ${ 变量引用前缀（defineVariableTyped） =====

    @Test
    fun defineVariableTypedExtractsInnerName() {
        assertEquals("", defineVariableTyped("\${"), "\${ 后空 = 全量提示")
        assertEquals("fw", defineVariableTyped("\${fw"), "变量名片段")
        assertEquals("fw", defineVariableTyped(" \${ fw"), "\${ 后带空格")
        // ${ 前面有内容（= 或中文等非分隔符）也触发，取最后一个 ${ 之后
        assertEquals("攻", defineVariableTyped("攻击范围=\${攻"), "$ 前是 = 也触发")
        assertEquals("fw", defineVariableTyped("text:攻击范围=\${fw"), "$ 前有内容也触发")
        assertEquals("fw", defineVariableTyped("text: \${fw"), "$ 前是空格也触发")
        assertNull(defineVariableTyped("fw"), "无 \${ 前缀不触发")
        assertNull(defineVariableTyped("攻击范围=攻"), "无 \${ 不触发")
        assertNull(defineVariableTyped("\${攻击.攻"), "含点 = 节属性引用（\${攻击.攻击距离}）不参与变量补全")
        assertNull(defineVariableTyped("text:\${攻击.攻击距离} 新\${内存.攻"), "内存链非变量")
        assertNull(defineVariableTyped(""))
    }

    // ===== 单位引用表达式属性判定（isUnitRefExpressionProperty） =====

    @Test
    fun unitRefExpressionPropertyDetected() {
        assertTrue(isUnitRefExpressionProperty("setCustomTarget1"), "设置自定义目标1")
        assertTrue(isUnitRefExpressionProperty("addWaypoint_target_fromReference"), "添加路径点来自参考")
        assertTrue(isUnitRefExpressionProperty("sendMessageTo"), "发送消息到")
        assertTrue(isUnitRefExpressionProperty("takeResources_includeReference"), "提取资源包括引用")
        // 本轮新增：表达式语义的 unit ref 属性
        assertTrue(isUnitRefExpressionProperty("teleportTo"), "传送到")
        assertTrue(isUnitRefExpressionProperty("fireTurretXAtGround_withTarget"), "指定攻击目标")
        assertTrue(isUnitRefExpressionProperty("alsoTriggerOrQueueActionWithTarget"), "也触发带目标行动")
        assertTrue(isUnitRefExpressionProperty("transportTargetNow"), "主动装运目标")
        assertFalse(isUnitRefExpressionProperty("convertTo"), "转换成 保持单位名")
        assertFalse(isUnitRefExpressionProperty("whenBuilding_temporarilyConvertTo"), "建造时临时转换为 保持单位名")
        assertFalse(isUnitRefExpressionProperty("onCreateSpawnUnitOf"), "创建时生成单位 保持单位名")
        assertFalse(isUnitRefExpressionProperty("unitShownInUI"), "UI中显示的单位 为混合型，保留单位名")
        assertFalse(isUnitRefExpressionProperty("basePosition"), "中心位置 为标记型（UnitName 本就不触发）")
        assertFalse(isUnitRefExpressionProperty("name"))
        assertFalse(isUnitRefExpressionProperty(""))
    }

}
