# 计划：写路径点等代码中间命中也能提示补全

## 目标
将补全系统从「仅前缀匹配」升级为「前缀优先 + 子串包含兜底」。
当用户输入的片段命中候选的**中间部分**（如写路径点开头的 `waypoint`，而候选是 `addWaypointFromRef`）时，也弹出补全，且前缀命中项排序优先于子串命中项。

## 匹配级别定义（统一 helper）
`completionMatchLevel(query, candidate)`，位于 `CompletionProvider.kt`：
- `2` = 前缀命中（`candidate.startsWith(query, ignoreCase=true)`），最优先。
- `1` = 子串包含命中（`query.length >= 2 && candidate.contains(query, ignoreCase=true)`），兜底。
- `0` = 不匹配。
- 空前缀视为全量匹配（返回 `2`）。

子串匹配**仅当 query 长度 ≥2 时启用**，避免单字符输入时列表被污染。

## 改动点

### 1. 主入口（已完成）
- [CompletionProvider.kt](file:///d:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/feature/completion/CompletionProvider.kt#L406)
  - 自定义补全表：`wordPrefix` 经 `completionMatchLevel` 过滤，并按匹配级别降序 + label 升序排序。
  - 翻译库兜底：同样改用分级匹配，`take(20)`。

### 2. 逻辑表达式源（已完成逻辑，待补 import）
- [LogicExpressionContext.kt](file:///d:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/feature/completion/value/LogicExpressionContext.kt#L85)
  - `unitMarkerItems` 与逻辑表达式源的过滤已改用 `completionMatchLevel`。
  - **待补**：文件顶部增加 `import com.rwmodstudio.feature.completion.completionMatchLevel`（当前缺失会编译失败）。

### 3. LogicBoolean 操作数过滤（待改）
- [LogicBooleanValueCompletionProvider.kt](file:///d:/ALSO2004/android-tool/RustedWarfareModStudio/app/src/main/java/com/rwmodstudio/feature/completion/value/LogicBooleanValueCompletionProvider.kt#L577)
  - `buildLogicItems` 的 `matches` 判断目前仅 `startsWith`。
  - 改为：对 `enBase`/`zhBase`/`rawName`/`zhName` 取**最高** `completionMatchLevel`（任一 >0 即保留），并按级别降序排序（前缀命中项靠前）。
  - 涉及路径点场景（`addWaypointFromRef` 等带 `waypoint` 的候选）。

## 验证
`assembleDebug` 编译通过；写路径点输入 `waypoint` 时，中间含 `waypoint` 的候选能弹出且前缀命中项置顶。