package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.feature.completion.CompletionProvider
import com.rwmodstudio.feature.completion.completionMatchLevel

/**
 * 单位标记（unit / marker 表达式）值补全。
 * 代码表 type="unit ref" 的属性分两类：
 * - 单位名语义（转换成/建造时临时转换为/创建时生成单位）→ 由 UnitNameValueCompletionProvider 补项目单位名；
 * - 单位标记表达式语义（值位置可填 当前动作目标/最后伤害源/父单位/self.父单位/接近单位(...)/创建标记(...)/
 *   获取绝对偏移(...)/内存.lastDock 等）→ 本 Provider 补 logicboolean 值数据中 type 为 unit/marker 的条目。
 * 覆盖：纯表达式属性（isUnitRefExpressionProperty，UnitName 同步排除）+ 中心位置/绘制线条（marker/marker ref）
 * + 混合型（UI中显示的单位/从单元添加名称/从单元添加描述，可填表达式也可填单位名，与 UnitName 并列）。
 * 表达式属性支持 `.` 链续写：self.父单位.、攻击中.自定义目标2.、内存.lastDock. 等只替换点后片段。
 * 中文视图经字典翻译 label（ValueDataLoader 已在加载时翻译）。
 */
class UnitRefValueCompletionProvider : BaseValueCompletionProvider() {

    /** 标记表达式语义但 type 非 unit ref 关键词、UnitName 本就不触发的属性（纯标记补全） */
    private val markerExpressionProperties = setOf(
        "baseposition", "drawlineto"
    )

    /** 混合型：既接受单位名也接受表达式（单位名由 UnitName 提供，这里并列补表达式） */
    private val mixedExpressionProperties = setOf(
        "unitshowninui", "textaddunitname", "descriptionaddfromunit"
    )

    override fun canProvide(request: ValueCompletionRequest): Boolean {
        // 括号内由 FunctionParameterCompletionProvider 处理
        if (request.isInsideParentheses()) return false
        // 用 toEnglishName() 归一化属性名（与 UnitNameValueCompletionProvider 排除口径一致），
        // 避免依赖 findProperty()（code_reference 中部分条目 name 为英文、无中文名，中文属性名匹配不上会漏触发）
        val en = request.toEnglishName().lowercase()
        return isUnitRefExpressionProperty(en) ||
            en in markerExpressionProperties ||
            en in mixedExpressionProperties
    }

    override fun provideItems(request: ValueCompletionRequest): List<CompletionProvider.CompletionItem> {
        val prefix = request.valuePrefix
        // 表达式链：self.父单位. / 攻击中.自定义目标2. / 内存.lastDock. → 成员列表（只替换点后片段），
        // 并混入内存变量名（self.内存. 后可直接补 lastDock 等）
        if (prefix.contains('.')) {
            val fragment = prefix.substringAfterLast('.')
            val logicItems = buildLogicMemberItems(request, fragment, fragment.length, detail = "单位标记")
            val memItems = request.memoryNames
                .map { it to completionMatchLevel(fragment, it) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
                .map { it.first }
                .map { name ->
                    createValueItem(
                        label = name,
                        detail = memoryDetail(request.memoryTypes[name.substringBefore('[')]),
                        insertText = name,
                        prefixLength = fragment.length,
                        valueType = request.memoryTypes[name] ?: "unit"
                    )
                }
            return (logicItems + memItems).distinctBy { it.label }
        }

        // 统一调用源：self + unit/marker/event × logicboolean + unit 型内存变量（内存.变量），
        // 与 设置单位内存:目标= 等 kvp RHS / +号菜单 / 演示面板同源（与 +号无关，统一即可）
        return unitMarkerItems(
            request.translationDict, request.context, prefix, request.rawValuePrefixLength,
            request.memoryNames, request.memoryTypes
        )
    }
}

/**
 * 单位引用表达式语义的 "unit ref" 属性判定（按 name_en 登记；UnitNameValueCompletionProvider 同步排除）。
 * 这类属性值位置填的是单位标记表达式（如 self.父单位、最后伤害源、获取绝对偏移(...)），而非项目单位名。
 */
internal fun isUnitRefExpressionProperty(nameEn: String): Boolean =
    nameEn.lowercase() in setOf(
        "setcustomtarget1", "setcustomtarget2", "sendmessageto",
        "transporttargetnow", "addwaypoint_target_fromreference",
        "takeresources_includereference",
        "teleportto", "fireturretxatground_withtarget",
        "alsotriggerorqueueactionwithtarget"
    )
