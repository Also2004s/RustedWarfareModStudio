# RustedWarfareModStudio 项目长期笔记

> 过程细节见 `.workbuddy/memory/YYYY-MM-DD.md`（append-only）。本文件只留长期有效的规矩。

## 老板定下的硬规矩
- **报告（docs/ 下）用「用户视角三层式」**：①为什么（大白话+触发条件）②改之前（手机上的现象）③改之后。标严重度 🔴🟡🟢，按功能名组织，**主栏禁一切代码术语**（NPE/缓存/mimeType…），技术细节压到文末「给开发者看」。样板 `docs/bug-audit-round2-2026-09-01.md`。
- **禁止擅自加兜底**（2026-09-17）：任何「回退/静默兼容/出错返原值/找不到就跳过/旧数据兼容」分支，必须先提出并得到同意。判据：**有数据或需求证明它必须存在吗？** 没有就不写。血的教训：给模板键加兜底把功能堵死，老板原话「我不理解你这么喜欢搞兜底干嘛」。
- **不要把能自己判定的事抛回去问老板**：范围以显式证据为准，没证据就是没有；结论自己焊死，别留选择题。
- **「安装」= 构建并装 debug 包**（`com.rwmodstudio.debug`）。没明说"正式版"就绝不碰 `com.rwmodstudio`。严格按字面范围执行，不自行扩大。
- **用户报的问题一定真实存在**：先信用户先自查。①查设备实际装的哪个包 ②APK 构建时间 ≥ 源码修改时间 ③grep 确认修复在源码。**老板报"没生效"的第一检查点 = 他验证的是哪个包**（日常开正式版）。

## 编码 / 架构约定
- **原子写只用于「用户数据」**（编辑文件/设置/历史/待办）；**可再生缓存**（翻译/继承/查重/补全表）直接 writeText。
- **热路径禁锁**：`TaskProgressManager` 无锁；`isFresh` 的 libVersion 直读 @Volatile。
- **一切生成文件只能在 `RWmod/` 分类子目录下**，路径走 `RwmodPaths`，不硬编码。
- **验证码机制**：改 `VerifyManager` 的 `xxx_CODE` = 下次启动强制重生对应数据。改会重置用户配置的验证码（`SECTION_FILTERS_CODE`、`MOCK_INI_CODE` 等）**先问老板**。
- 弹窗统一用 `AppDialog`（`ui/components/CommonUiComponents.kt`），底部用 `DialogActionButton`；无 weight 子项 → `heightIn(max)+verticalScroll`，含 `LazyColumn(weight(1f))` → 必须固定 `height(...)`。
- `loadBlocklist` 只在主线程同步调；MainActivity onCreate 四行顺序已验证，别动。
- **同一文件禁止并行 Edit**（会丢改动且工具报成功），必须串行。
- **GitHub 是超级旧版**，`git diff` 无意义，改动判断靠会话内清单 + 调用链。
- **mod-info.txt 是特殊文件**：补全按文件名启用，不能把 mod/music 注册成全局节。
- **两个包共用同一份 `/sdcard/RWmod` 配置**（路径不带包名，2026-09-17 更正）：`settings.json`/`highlight_rules.json`/翻译/待办全共享。只更一个包且不 bump `DEFAULTS_VERSION`，另一个会"以为已刷新"而跳过。

## 自定义高亮维护（`ini.tmLanguage.json` / `HighlightRuleManager`）
- **每改一次正文就必须把 `DEFAULTS_VERSION` +1**（不是改一批 bump 一次）——它是"要不要重刷默认规则"的唯一开关。踩坑：一天改 3 次只 bump 一次 → 手机生效的停在第一版。
- 每张词表在 **键值区 + 文件顶层各一份**，必须逐字一致，否则按 signature 裂成两个 UI 分组。手法：锚点片段 + `replace_all`。
- **别动槽位/条数**：同 scope 内插新规则要排在"兜底词"之前，插中间会让 instance 编号错位。扩写现有正文即可。
- **加词三关**：①不许重复定义（同一词挂两个 UI 分组 = 改色打架）②归类看语义（返回判断结果的进「函数」，取数值/名字的进「内置属性」）③范围锚定不扩散。
- **类型顺序的唯一来源 = `DarkThemeColors.labels` + `targetScopes`（两表键序必须逐项一致）**（2026-09-18 按真实项目实测重排）：`ui`/`plainText` 置顶 → 其余按出现次数降序 → 节族（节括号/节后缀）→ 数字 → 符号组（组内不按次数）→ 注释最末。**别在任何页面里另排一套顺序**（单测 `labelsAndTargetScopesShareTheSameOrder` / `categoryOrderFollowsMeasuredFrequencyLayout` 钉死）。
- **函数 vs 属性值的判定规则（2026-09-18 老板亲定，为此骂过我两次，别自己猜）**：
  - **函数 = 计算 / 工具类**：`int`（取整，老板口头说的"ini"就是它）、`rnd`、`min`、`max`、`sin`、`cos`、`squareRoot`/平方根、
    `select`/选择、`createMarker`/创建标记、`distance`/距离、`getOffsetRelative`/获取相对偏移、
    `readUnitMemory`/读取单位内存，以及 `eventData`/事件数据、`debugPassthrough`/调试通行。
  - **属性值 = 状态判断 / 计数 / 取状态**：`队伍中此单位数量`、`自身有标签`、`自身在天上`、`游戏地图高度/宽度`、
    `自身连接数`、`核武启用`、`队伍获胜`… **它们的中英文都归「属性值」**（中文原本就在那边，是对的）。
  - ⚠️ **不能凭"后面有没有括号"判断** —— 状态类同样带括号调用（`队伍中此单位数量(需标签="x")`）。
    判定依据是语义（算东西 = 函数，问状态 = 属性值），不是写法。
  - 拿不准就问老板。**这是 RW 语义，我不懂，一次都别自己拍。**
  - 历史坑：v5 那次「函数 +85」把 48 个英文状态词（isXxx/hasXxx/numberOfXxx）误塞进函数，v8 已挪回属性值；
    同时补进词表里原本缺失的计算函数 int/rnd/min/max/sin/cos（真实项目用了 398 次却没高亮）。（2026-09-18 统一，共 10 处）：`keyword`=属性、`value`=属性值、`decoration`=连续引号、`type`=内存类型、`memory`=内存/变量、`shortcutVariable`=变量名、`movement`=移动类型、`team`=枚举值、`reference`=单位标记、`sectionBracket`=节前缀。
  **⚠️ 命名权在老板，不在我**（2026-09-18 被骂过一次）：我一度自作主张把 `sectionBracket` 改成「节名与括号」、`reference` 改成「引用目标」，理由是"更准确"——老板不接受。**我的职责是核实「这个类型到底管什么」并把依据摆出来，名字怎么叫由他定**；觉得不妥就提一次，他坚持就照办，别用"语义更准"顶回去。
  **改 `labels` 只影响显示**（规则文件存的是字段名）；**别改 `HighlightRulesDefaults` 的 displayGroup**——那是落进用户规则文件的分组名，改了会让老用户看到新旧两套分组名。
- **4 个死类别已删（2026-09-18 二次处理，老板「把这个处理掉」）**：`property`（属性/常量）/ `quote`（字符串引号）/ `modifier`（修饰符）scope 在语法里 **0 次**、整类空转 → **从 labels/targetScopes 整体移除**（此前只从调色盘排除、规则页保留，现已彻底删）；`decoration`（连续引号）**并入普通文本**——不再是独立类型，`SoraEditorInitializer.applyScopeColors` 对 `punctuation.ignored.ini` 强制取正文色（4 个主题写死 #FFFFFF，不接管会浅色主题白底白字）。SLOTS 删 quote 2 槽 + decoration 4 槽（98 时代 → 90），`nonColorableCategories` = 空集。**代价：bump `DEFAULTS_VERSION`（每轮都要）**。⚠️ ghost scope 还有 14 处在**活类别**里（value 正斜杠 / string multiline / **movement 4 缺 3** / memory 3 缺 2 / sectionBracket prefix / comment block+标记），主题里另有约 21 条死配色 —— 登记未清（老板说其他不用管）。
- ⚠️ **统计 ini 高亮必须分层模拟**（2026-09-18 踩坑）：`ini.tmLanguage.json` 的「键值对」是 `begin`/`end` 块（顶层 `patterns[5]`），**块内自有 37 条规则，进入块后顶层规则不参与匹配**（注释 `(#|;).*$` 只在顶层）。把两层规则**摊平**会得出「颜色值不可达」的错结论 —— 分层后 `颜色:#FF0000` 正常上色，全项目命中 322 次。脚本 `.workbuddy/tools/hl_usage_scan.py`（已按分层实现，传目录可重跑）。
- **调色盘 = 规则页（2026-09-18 更新）**：`DarkThemeColors.colorableCategories` 是「自定义高亮」可调色类型的唯一来源；`nonColorableCategories` **已清空**（4 个死类别删的删、并的并），调色盘与规则页现在完全一致（25 项）。
- **`decoration`（连续引号）已并入普通文本（2026-09-18 二次处理）**：scope 名 `punctuation.ignored.ini`，语法用它圈 `"""` / `'''` 多行引号的边界（实测只吃**前两个**引号，第 3 个无 scope 走默认色）。它**不再是独立类型**（labels/targetScopes/SLOTS 里都删了）；着色由 `SoraEditorInitializer.applyScopeColors` 主循环特判接管——scope 命中 `punctuation.ignored.ini` 就取 `plainText` 色。
  **踩过的坑（仍然有效）**：4 个主题文件里给它写死 `#FFFFFF`，取色逻辑不接管的话 ⇒ 深色主题看不出、**浅色/纯净主题下这三个引号变白字**（白底白字）。别再把它的颜色写死成某个固定值。
- 统计真实项目各类型出现次数的脚本：`.workbuddy/tools/hl_usage_scan.py`（按语法真实规则顺序逐行扫描，TextMate 近似语义）。
- 算缺口：脚本只生成候选集，**最终事实逐词 `grep -o -F 词 | wc -l`**。
- **关键字边界必须 `\b`**：joni 按 Unicode 判词字符（汉字也是），用 `(?<![A-Za-z0-9_])` 会让中文词钻进更长词里单独上色。

## sora-editor 陷阱
- `applyEditorTheme` → `getColorScheme` → `ThemeRegistry.setTheme` 无条件 dispatch → 各实例 `colors.clear()+applyDefault()`（含背景）。「重建方案」污染其它实例，「仅 setColor」不会。自定义颜色走 `SoraCodeEditor.applyEditorColors(...)`，实例重新激活时在 `LaunchedEffect(active, editorRef.value)` 恢复。
- 主题解析：tm4e-core `IThemeSource.fromInputStream` + `RawThemeReader.readTheme`，配色走 `Theme.match(ScopeStack.from(scope))`。事件拦截是 `markAsConsumed()`。
- 单测：JVM 可读 assets 做文本断言；`org.json` 在 JVM stub **是空实现，不能解析 JSON**。

## 翻译引擎 / 补全（2026-09-17 核实）
- **整值专用词**（`specialValues` 9 组）只进 `valueTranslations`，不进 enToZh/zhToEn ⇒ 只在整值恰好命中时生效；括号内 `type=LAND` 保持英文，写成 `type=陆地` 反译不回来。
- 补全语言按场景：整值位置可中文，括号内整值词必须英文（`canTranslateValuesInPhrase` 场景闸）；引号闸只对 enum/type/relation 生效，`tag/actionTag/attachmentSlot` 等项目自定义名必须原样保留。
- **反译的「归一化」是设计行为不是 bug**（统一返回裸英文形态）。
- 模板键**一律以英文实例化形态落盘**，中文名只用于列表显示；插入时占位符原地留空 + 光标停在空位。占位符判定口径：**数据里有实例证明该位被替换才纳入**（全量 34 条 = 已生效 32 + 待做 2 条 mutatorX）。见 `计划-序号模板键补全.md`、`docs/模板键覆盖排查-2026-09-17.md`。
- **未修（待老板定）**：属性整值补全的 label 与匹配都用中文 → 英文前缀匹配不到；修法需 bump `MOCK_INI_CODE`。

## material3 1.2.1
- `RectangleShape` 在 `androidx.compose.ui.graphics`；`ModalDrawerSheet` 用 `drawerTonalElevation`；`NavigationDrawerItemDefaults.colors()` 只 8 个参数。

## 环境
- bash 缺 coreutils：命令前加 `export PATH="/d/Git/usr/bin:/d/Git/bin:$PATH"`（否则命令静默无输出）。`xargs -I{}` 不可用。
- 删除文件可能被沙箱拦（SIGTERM，无回显）→ 走审批。**老板要求：要删就直接删，不要先备份。**
- JDK `tools/jdk/jdk-17.0.19+10`；守护进程残留抢锁 → `jps -l` + `taskkill /F /PID` → 删 `~/.gradle/daemon/8.14.5/registry.bin` → `--no-daemon` 前台跑，输出重定向到文件再 tail。
- 设备 V2505A（Android 16, vivo）；adb `C:\Users\Also2\AppData\Local\Android\Sdk\platform-tools\adb.exe`。
- **不要对老板真机做手势自动化**（会被识别成长按弹删除）；验证 UI 只用 `screencap` + `uiautomator dump`。
- 回收站 = `RWmod/exports` 目录本身；侧边栏「文件浏览 / 回收站」，`browserRootUnbounded` 只由侧边栏置 true。
