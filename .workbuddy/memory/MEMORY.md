# RustedWarfareModStudio 长期笔记（过程细节见同目录 YYYY-MM-DD.md）

## 老板硬规矩
- 报告用「用户视角三层式」（为什么/改前/改后）+ 🔴🟡🟢；**主栏禁代码术语**，技术细节压文末。
- **禁止擅自加兜底**（09-17）：回退/静默兼容/出错返原值必须**先提出并得到同意**；判据 = 有数据证明它必须存在吗？
- **报告里列了 N 处方案 ≠ N 处都获授权**（10-23 被批）：他说「开始修复」时只修他点名的那个问题；顺带发现的其他缺陷/配套改动一律留在文档里等单独批准，不许打包执行。越界的要立刻回退。
- **他说「给我方案」= 只出方案，一行代码都不许改**（10-29 被重批）：我把"给我一个可靠的解决方案"读成"去修"，擅自把项目改成 edge-to-edge（改 8 处窗口/insets 架构），被斥「绝对不允许，对项目是不可逆转的负面伤害」。
  → **横切关注点（窗口 insets / edge-to-edge / 主题体系 / 导航结构 / 全局布局模型）一律先出方案 + 代价 + 风险，等他点头再做**；局部功能改完也必须先说明动了哪些层。
- **禁止 edge-to-edge**（10-29 死命令）：不许 `setDecorFitsSystemWindows(false)`、不许全局 edge-to-edge 迁移、不许其等价物
  （`SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` / `FLAG_TRANSLUCENT_STATUS` / `FLAG_LAYOUT_NO_LIMITS` 都是同一机制的皮）。
  老板实测在 V2505A 上导致核心功能退化，判定为**不可逆伤害**；曾擅自实施两次被重批，代码已全部回退（APK 字节级一致）。
  连带结论：**「折叠浮层时隐藏状态栏」这个需求在本项目不做**——非 edge-to-edge 下做不出真全屏，只会留一条底色。
- 不要把能自己判定的事抛回去问；范围以显式证据为准，结论自己焊死。
- 「安装」= 装 debug 包（`com.rwmodstudio.debug`）；没明说"正式版"绝不碰 `com.rwmodstudio`。
- 用户报的问题一定真实存在：先信用户先自查（装的哪个包 → APK 时间 ≥ 源码时间）。"没生效"第一查点 = 他验证的哪个包。
- **不许操控老板真机验证**（09-23 明令）：不用 adb 启动/截图/uiautomator 核对他的描述。
- **先对齐名词再动手**（10-25 连踩三次）：他说"自定义高亮"指的是**调色弹窗** `DarkTokenColorDialog`（标题就叫「自定义高亮」），不是规则页。项目里同名概念至少两套：①规则（按类别写关键字/正则）②调色（给类型配色）。听到专有名词先 grep title/字符串定位。
- 他说"某某的分栏/弹窗" = **那个对象的整体布局**，别去改它里面的子对话框；也别改完再把外层分栏撤掉。
- **平板适配只看开关**：`SettingsManager.devTabletMode`，与横竖屏无关（用 orientation 判断曾导致"开了平板模式却没分栏"）。

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
- **绝不使用 `cat >> 文件 <<'EOF'` 追加**（10-25 血案）：这台机器上它从**文件头覆写**，把 package + 全部 import 吃掉还截断半行。改用 python 读写或 Write/Edit。
- MuMu 模拟器 adb：`127.0.0.1:7555`（伪装 vivo V2366GA，但 ABI=x86_64 + Android 15 可辨）；**别碰**无线连接的真机做验证操作（启动/截图/uiautomator）。
- adb 不在 PATH：`/c/Users/Also2/AppData/Local/Android/Sdk/platform-tools/adb.exe`。当前真机 = V2505A（PD2505，Android 17），无线标识 `adb-10AG3A27S8006JQ-reRiGJ._adb-tls-connect._tcp`；另有 3 台 PD2366 伪装环境（`127.0.0.1:16416`、`:7555`、`emulator-5556`）。「安装」= 装 `com.rwmodstudio.debug`，装真机。
- 编译报 `Unable to delete directory app\build\tmp\kotlin-classes\debug` → 杀 KotlinCompileDaemon/GradleDaemon + 删该目录后重编。

## 平板模式双栏（10-25 已做 8 处）
- 条件统一 `val isSplit = SettingsManager.devTabletMode`。
- 已分栏：编辑器双栏、设置、开发者模式、项目管理、补全类型分类、代码表（左列表右详情）、高亮规则页（左类别目录）、**自定义高亮调色弹窗**（左 210dp 分类竖排 + 右 240dp 取色盘，`AppDialog(wide=true)`）。
- 套路：`@Composable fun Body()` 包住原 Column + `if (isSplit) Row { 左栏 ; Body() } else Body()`；SectionHeader 类页面用 `SectionAnchor(i)`（0 高 Spacer + `onGloballyPositioned`）+ `animateScrollTo` 跳转。
  - ⚠️ `onGloballyPositioned` 写 state 必须判 `旧值 != 新值`，否则布局→重组死循环。
- `AppDialog` 加 `wide: Boolean = false`：true → `usePlatformDefaultWidth=false` + `widthIn(max=900dp)`。
- 未做：翻译库、自定义补全（编辑是小表单，搬常驻面板要重写校验/删除，收益低）。

## 坐标/仿真引擎（10-26）
- **单位标记白名单** `CoordUnits.UNIT_MARKER_NAMES`，来源 = 代码表 `logicboolean` 里代表单位的那批：self/当前动作目标(thisActionTarget)/活动路径点目标(activeWaypointTarget)/最后伤害源/父单位/附件/运输/攻击中/自定义目标1-2/空单位/事件来源。
  - `collectUnitNames` 只收白名单 → 输入到一半（"当前""当前动作"）不建点；`syncDiscoveredTargets` 会删掉改名后残留的点。
  - 示例代码与快捷按钮里的「敌人」已改「当前动作目标」（敌人不是合法标记）。
- 目标发现走 AST（`间距/方向` 位置参数 + `xxx.x/.y/.dir`），不用正则；求值端**不再兜底成「敌人」**。
- 区域渲染：有解 → marching squares 画 f=0 + 填充；**无解**（三角不等式等号情形）→ 每列对 f 三分搜索取最小点连成线（`zeroPath`）。**不给用户容差**——容差是公式的事，工具负责把最小像素画出来（老板 10-25 明确）。
- `hasSolution` 用 `minV < -1e-6` 判定（浮点抖动别当有解）。
- 考察点自动推断 `inferProbeName`：出现次数最多的非 self 单位，平局取先出现的；手动选过才 pin。
