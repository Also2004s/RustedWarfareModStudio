package com.rwmodstudio.core

/**
 * 「排序自定义」的默认内容。
 *
 * 来源：真实项目 `人机的玩笑`（335 个文件）按节统计的键出现频次，取各节指定条数。
 * 首次运行播种 / 页面「重置为默认」使用。
 *
 * 每条同时登记中文显示名（[SortEntry.zh]）与英文键（[SortEntry.en]）：
 * 补全项按 name / nameEn / label 任一命中即算命中，保证中文视图与英文视图都生效。
 * 列表顺序即优先级（靠前的排前面）。
 */
object SortCustomizationDefaults {

    /** 默认表版本：默认内容有实质调整时上调（用于判断是否需要重播默认表）。 */
    const val VERSION = 1

    val DEFAULT: Map<String, List<SortEntry>> = mapOf(
        "隐藏行动" to listOf(
            SortEntry("需要条件", "requireConditional"),
            SortEntry("自动触发", "autoTrigger"),
            SortEntry("添加路径点动作类型", "addWaypoint_type"),
            SortEntry("清除所有路径点", "clearAllWaypoints"),
            SortEntry("临时标签添加", "temporarilyAddTags"),
            SortEntry("自动触发事件", "autoTriggerOnEvent"),
            SortEntry("临时标签删除", "temporarilyRemoveTags"),
            SortEntry("添加路径点检索标签", "addWaypoint_target_nearestUnit_tagged"),
            SortEntry("添加路径点靠近队伍", "addWaypoint_target_nearestUnit_team"),
            SortEntry("增加资源", "addResources"),
            SortEntry("转换成", "convertTo"),
            SortEntry("添加路径点来自参考", "addWaypoint_target_fromReference"),
            SortEntry("产生单位", "spawnUnits"),
            SortEntry("添加路径点路径可达", "addWaypoint_target_mapMustBeReachable"),
            SortEntry("也添加进队列", "alsoQueueAction"),
            SortEntry("删除自身", "deleteSelf"),
            SortEntry("建造速度", "buildSpeed"),
            SortEntry("设置单位内存", "setUnitMemory"),
            SortEntry("用逻辑设置资源", "setResourcesWithLogic"),
            SortEntry("生产单位", "produceUnits"),
            SortEntry("添加路径点失败触发", "addWaypoint_triggerActionIfFailed"),
            SortEntry("也执行动作", "alsoTriggerAction"),
            SortEntry("添加路径点单位类型", "addWaypoint_unitType"),
            SortEntry("添加路径点匹配触发", "addWaypoint_triggerActionIfMatched"),
            SortEntry("添加全局标签", "addGlobalTeamTags"),
            SortEntry("复制但跳过节", "@copyFrom_skipThisSection"),
            SortEntry("添加指定队伍的随机单位", "addWaypoint_target_randomUnit_team"),
            SortEntry("添加指定标签的随机单位", "addWaypoint_target_randomUnit_tagged"),
            SortEntry("提取资源标签", "takeResources_excludeUnitsWithoutTags"),
            SortEntry("发送消息到", "sendMessageTo"),
            SortEntry("提取资源触发行为", "takeResources_triggerActionForEach"),
            SortEntry("带标签发送消息", "sendMessageWithTags"),
            SortEntry("重置自定义计时器", "resetCustomTimer"),
            SortEntry("清除当前路径点", "clearActiveWaypoint"),
            SortEntry("提取资源范围", "takeResources_includeUnitsWithinRange"),
            SortEntry("提取资源仅搜索", "takeResources_searchOnly"),
            SortEntry("用逻辑添加资源", "addResourcesWithLogic"),
            SortEntry("提取资源目标量", "takeResources_maxUnits"),
            SortEntry("提取资源队伍", "takeResources_includeUnitsWithinRange_team"),
            SortEntry("移除全局标签", "removeGlobalTeamTags")
        ),
        "行动" to listOf(
            SortEntry("text", "text"),
            SortEntry("建造速度", "buildSpeed"),
            SortEntry("显示类型", "displayType"),
            SortEntry("pos", "pos"),
            SortEntry("可见性", "isVisible"),
            SortEntry("描述", "description"),
            SortEntry("价格", "price"),
            SortEntry("允许多个队列", "allowMultipleInQueue"),
            SortEntry("界面闪烁", "isGuiBlinking"),
            SortEntry("转换成", "convertTo"),
            SortEntry("可用", "isActive"),
            SortEntry("显示给盟友", "isAlsoViewableByAllies"),
            SortEntry("图标", "iconImage"),
            SortEntry("AI被禁用", "ai_isDisabled"),
            SortEntry("额外图标图像", "iconExtraImage"),
            SortEntry("临时标签添加", "temporarilyAddTags"),
            SortEntry("锁定", "isLocked"),
            SortEntry("额外图标颜色", "iconExtraColor"),
            SortEntry("显示给敌人", "isAlsoViewableByEnemies"),
            SortEntry("从单元添加名称", "textAddUnitName"),
            SortEntry("发送消息至所有", "showMessageToAllPlayers"),
            SortEntry("UI中显示的单位", "unitShownInUI"),
            SortEntry("增加资源", "addResources"),
            SortEntry("生产单位", "produceUnits"),
            SortEntry("从单元添加描述", "descriptionAddFromUnit"),
            SortEntry("AI高优先级", "ai_isHighPriority")
        ),
        "核心" to listOf(
            SortEntry("name", "name"),
            SortEntry("tags", "tags"),
            SortEntry("复制与", "copyFrom"),
            SortEntry("半径", "radius"),
            SortEntry("价格", "price"),
            SortEntry("生命值", "maxHp"),
            SortEntry("建造速度", "buildSpeed"),
            SortEntry("自动触发间隔", "autoTriggerCooldownTime"),
            SortEntry("科技等级", "techLevel"),
            SortEntry("质量", "mass"),
            SortEntry("class", "class"),
            SortEntry("覆盖单位", "overrideAndReplace"),
            SortEntry("自动触发检查率", "autoTriggerCheckRate"),
            SortEntry("不可选择", "isUnselectable"),
            SortEntry("界面显示内部调用", "displayLocaleKey"),
            SortEntry("是生物", "isBio"),
            SortEntry("显示在沙盒中", "showInEditor"),
            SortEntry("禁止加载", "dont_load"),
            SortEntry("资源活着时借用", "borrowResourcesWhileAlive"),
            SortEntry("碰撞体积软化", "softCollisionOnAll"),
            SortEntry("禁用碰撞", "disableAllUnitCollisions"),
            SortEntry("界面显示描述", "displayDescription"),
            SortEntry("单位选择时显示圆圈", "displayRadius"),
            SortEntry("在演示版中可用", "availableInDemo"),
            SortEntry("界面显示名称", "displayText"),
            SortEntry("是建筑", "isBuilding"),
            SortEntry("视野", "fogOfWarSightRange"),
            SortEntry("建筑覆盖范围", "constructionFootprint"),
            SortEntry("禁止接受指令", "canNotBeGivenOrdersByPlayer")
        ),
        "运动" to listOf(
            SortEntry("运动类型", "movementType"),
            SortEntry("移动速度", "moveSpeed")
        ),
        "攻击" to listOf(
            SortEntry("可攻击", "canAttack"),
            SortEntry("可攻击水下单位", "canAttackUnderwaterUnits"),
            SortEntry("可攻击空中单位", "canAttackFlyingUnits"),
            SortEntry("可攻击表面单位", "canAttackLandUnits"),
            SortEntry("攻击距离", "maxAttackRange"),
            SortEntry("攻击间隔", "shootDelay")
        ),
        "图像" to listOf(
            SortEntry("image", "image"),
            SortEntry("死亡图像", "image_wreak"),
            SortEntry("Y轴阴影偏移", "shadowOffsetY"),
            SortEntry("X轴阴影偏移", "shadowOffsetX"),
            SortEntry("image_shadow", "image_shadow"),
            SortEntry("总帧数", "total_frames"),
            SortEntry("可见性", "isVisible"),
            SortEntry("炮塔图像", "image_turret"),
            SortEntry("图像缩放", "imageScale")
        ),
        "可建造" to listOf(
            SortEntry("name", "name"),
            SortEntry("pos", "pos"),
            SortEntry("可见性", "isVisible"),
            SortEntry("建造方式", "forceNano")
        ),
        "资源" to listOf(
            SortEntry("隐藏", "hidden"),
            SortEntry("显示名称", "displayName"),
            SortEntry("显示颜色", "displayColor")
        ),
        "全局资源" to listOf(
            SortEntry("隐藏", "hidden"),
            SortEntry("显示颜色", "displayColor"),
            SortEntry("显示名称", "displayName"),
            SortEntry("显示为整数", "displayRoundedDown"),
            SortEntry("优先级", "priority"),
            SortEntry("为零时显示", "displayWhenZero")
        ),
        "贴花" to listOf(
            SortEntry("图像缩放", "imageScale"),
            SortEntry("可见性", "isVisible"),
            SortEntry("image", "image"),
            SortEntry("绘制图层", "layer"),
            SortEntry("中心位置", "basePosition"),
            SortEntry("透明度", "alpha"),
            SortEntry("角度偏移", "dirOffset"),
            SortEntry("复制节", "@copyFromSection")
        ),
        "附属" to listOf(
            SortEntry("x", "x"),
            SortEntry("y", "y"),
            SortEntry("闲置方向", "idleDir"),
            SortEntry("闲置方向反转", "idleDirReversing"),
            SortEntry("复制节", "@copyFromSection"),
            SortEntry("显示所有操作来源", "showAllActionsFrom"),
            SortEntry("创建时生成单位", "onCreateSpawnUnitOf"),
            SortEntry("可被攻击或损坏", "canBeAttackedAndDamaged"),
            SortEntry("不可选择", "isUnselectable"),
            SortEntry("绘制于顶层", "setDrawLayerOnTop"),
            SortEntry("保持子存活", "keepAliveWhenParentDies"),
            SortEntry("高度", "height"),
            SortEntry("可攻击", "canAttack")
        ),
        "放置规则" to listOf(
            SortEntry("搜索距离", "searchDistance"),
            SortEntry("失败信息", "cannotPlaceMessage"),
            SortEntry("检索标签", "searchTags"),
            SortEntry("最大数量", "maxCount"),
            SortEntry("检索队伍", "searchTeam")
        ),
        "AI" to listOf(
            SortEntry("建造优先级", "buildPriority"),
            SortEntry("每个基地最大数量", "maxEachBase"),
            SortEntry("升级自", "upgradedFrom"),
            SortEntry("AI升级优先级", "ai_upgradePriority"),
            SortEntry("全图最大数量", "maxGlobal"),
            SortEntry("用作建造者", "useAsBuilder"),
            SortEntry("全图没有时优先级", "noneGlobalExtraPriority"),
            SortEntry("基地内没有时优先级", "noneInBaseExtraPriority"),
            SortEntry("推荐在每个基地的数量", "recommendedInEachBaseNum"),
            SortEntry("推荐条件不满足时优先级", "recommendedInEachBasePriorityIfUnmet")
        )
    )

    /** 页面可选的节名（顺序与默认表一致，其后为无默认表的节）。 */
    val SECTION_NAMES: List<String> = listOf(
        "隐藏行动", "行动", "核心", "运动", "攻击", "图像", "可建造", "资源",
        "全局资源", "贴花", "附属", "放置规则", "AI",
        "炮塔", "抛射体", "效果", "动画", "腿"
    )

    fun defaultFor(section: String): List<SortEntry> = DEFAULT[section].orEmpty()
}
