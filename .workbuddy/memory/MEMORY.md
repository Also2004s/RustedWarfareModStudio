# RustedWarfareModStudio 长期笔记（过程细节见同目录 YYYY-MM-DD.md）

## 老板硬规矩
- 报告用「用户视角三层式」（为什么/改前/改后）+ 🔴🟡🟢；**主栏禁代码术语**，技术细节压文末。
- **禁止擅自加兜底**（09-17）：回退/静默兼容/出错返原值必须**先提出并得到同意**；判据 = 有数据证明它必须存在吗？
- **报告里列了 N 处方案 ≠ N 处都获授权**（10-23 被批）：他说「开始修复」时只修他点名的那个问题；顺带发现的其他缺陷/配套改动一律留在文档里等单独批准，不许打包执行。越界的要立刻回退。
- 不要把能自己判定的事抛回去问；范围以显式证据为准，结论自己焊死。
- 「安装」= 装 debug 包（`com.rwmodstudio.debug`）；没明说"正式版"绝不碰 `com.rwmodstudio`。
- 用户报的问题一定真实存在：先信用户先自查（装的哪个包 → APK 时间 ≥ 源码时间）。"没生效"第一查点 = 他验证的哪个包。
- **不许操控老板真机验证**（09-23 明令）：不用 adb 启动/截图/uiautomator 核对他的描述。

## 架构 / 编码
- 原子写只用于用户数据（文件/设置/历史/待办）；可再生缓存直接 writeText。热路径禁锁（libVersion 直读 @Volatile）。
- 生成文件只能在 `RWmod/` 分类子目录，路径走 `RwmodPaths`。
- 改 `VerifyManager` 的 `xxx_CODE` = 下次启动强制重生对应数据；改会重置用户配置的码（`SECTION_FILTERS_CODE`/`MOCK_INI_CODE`）先问老板。
- 弹窗统一 `AppDialog` + `DialogActionButton`；无 weight 子项 → `heightIn(max)+verticalScroll`，含 `LazyColumn(weight)` → 固定 `height`。
- 同一文件禁止并行 Edit（会丢改动）。GitHub 是超级旧版，`git diff` 无意义。
- 两个包共用同一份 `/sdcard/RWmod`；只更一个包且不 bump `DEFAULTS_VERSION`，另一个会跳过刷新。

## 编辑器 UI 常量（联动，改前必读）
- 顶部两栏：菜单栏 **40dp** + tab 栏 **20dp**（60=40+20），四处同步（`MainApp` 浮层 / 非编辑器页顶栏 / `EditorScreen` tab / 内容 padding `*(1-topFold)`）。两栏 + 状态栏固定 `#1E1E1E`，**刻意不跟主题**。
- 底部快捷栏 `barHeight=34dp`；宽度自适应 `perButtonWeight=0.25f/3f`。
- 底部栏/「方块+」配色**跟 App 深浅主题**（不是编辑器背景色）：底 = `themeBarBackgroundColor`，字 = `themeBarContentColor`；着色点 6 处同步。**老板说"可以了"就收工，别顺手重构**。
- 撤销/重做在**顶部菜单栏**（撤销→重做→书签），靠 `onCanUndoChange/onCanRedoChange` + trigger，**触发值必须消费**。底部符号栏已无重做按钮。

## 自定义高亮
- 改正文必 bump `DEFAULTS_VERSION`；词表键值区+顶层各一份必须逐字一致；新词插兜底词之前（别动槽位数）。
- 类型顺序唯一来源 = `DarkThemeColors.labels` + `targetScopes`（键序一致），别另排（单测钉死）。
- **函数 = 计算/工具类；属性值 = 状态判断/计数/取状态**；依据语义不是有无括号，拿不准必须问老板。命名权在老板；改 `labels` 只影响显示。
- 4 个死类别已删（property/quote/modifier；decoration 并入普通文本）；SLOTS 90 条。
- 统计 ini 高亮**必须分层**（键值对是 begin/end 块，块内 37 条独立）。关键字边界必须 `\b`。

## sora-editor 陷阱
- **撤销栈指针滞后**：`undo/redo` 先改内容（触发变更事件）后移指针 → 回调里读 `canUndo/canRedo` 是旧值。修法：`event.isCausedByUndoManager` 那一支 `editor.post{}` 延后一帧。
- `applyEditorTheme` → `ThemeRegistry.setTheme` 无条件 dispatch 会污染所有实例；自定义色走 `SoraCodeEditor.applyEditorColors`。
- 单测：JVM 可读 assets 做文本断言；`org.json` 在 JVM 是空实现。
- IME：0.24.6 自带完整支持，不用自己写。vivo「不支持文本编辑」由厂商 ROM 私有接口判定，**无解**。

## 智能断行（09-23 已实现）
- 顺序：块边界（括号外逗号之后 / and·or / 含 and·or 的括号组）→ 块内语义断点（运算符 / 括号内逗号 / 参数括号组起点）→ 标点+空格填满 → `.` → CJK 硬断；**逗号留上一行末尾**；`标识符(...)` 参数组为原子区间（组超宽时解除保护）。
- 坑：逗号后置会打乱兜底断点的升序 → 必须用有序集（`farthestFitting` 依赖升序 + 早停）。见 `docs/智能断行优化方案与提前断行排查-2026-09-23.md`。

## 翻译引擎 / 补全
- 整值专用词只进 `valueTranslations`；括号内整值保持英文。引号闸只对 enum/type/relation 生效，`tag/actionTag` 等自定义名原样保留。
- 反译「归一化」是设计不是 bug。模板键以英文实例化形态落盘。
- **specialValues 分支必须按方向分流**（10-23 修）：`valueTranslations` 双向存储（true↔真 同一条），中→英方向若还去查会反查成中文 → 粘贴进中文视图的 `true` 保存后变「真」、「LAND」变「陆地」（9 个值全中招）。正确写法 `return if (enToZh) getValueTranslationBack(processed) else processed`。
- **布尔 token 边界用「标点白名单」是已知缺陷，但 10-23 未获授权修**：`(?<=[=,\(])…(?=[,\)\}])` 会漏 `if 真`/`%{真}`/`[真]`/`真 and 假`。方案（独立词边界 + `replaceOutsideQuotes` + `trimInvisible`）写在 `docs/修复报告-布尔与枚举值被反译成中文-2026-10-23.md` 问题二，等老板单独批。
- 「补全类型分类」页 = 真实补全判定（默认全 Provider 开）；改默认开关必 bump `MOCK_INI_CODE`。
- 未修：属性整值补全 label 与匹配都用中文，英文前缀匹配不到。

## 环境
- bash 缺 coreutils：命令前 `export PATH="/d/Git/usr/bin:/d/Git/bin:$PATH"`；`xargs -I{}` 不可用。
- 删文件可能被沙箱拦（SIGTERM、无回显）→ 走审批。**要删就直接删，不先备份**。
- JDK `tools/jdk/jdk-17.0.19+10`；守护进程抢锁 → `jps -l`+`taskkill`+删 `~/.gradle/daemon/8.14.5/registry.bin`+`--no-daemon`。
- `dexBuilderDebug` 报 `desugar_graph...graph.bin` 拒绝访问 → 删 `app/build/intermediates/desugar_graph` 重建，**不用 clean**。
- **改完主源码后测试编译成片 `Unresolved reference`**（连确实存在的函数都找不到）= Kotlin 增量缓存损坏。删 `app/build/kotlin` + `app/build/tmp/kotlin-classes` 后全量重编即恢复，别怀疑源码。
- 全量单测有两个**既有失败**（与本次改动无关，stash 对照已证）：`CompletionSymbolsTest.extractFileSymbolsParsesAllCategories`、`CustomCompletionsFormatTest.extraCompletionsAllUseEmptyValueFormat`。353 个测试里固定 2 个红，别当回归。
