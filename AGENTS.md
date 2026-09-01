# AGENTS.md — RustedWarfareModStudio（铁锈工坊）

> 本文档**以当前源码为准**，供在仓库中工作的 AI 代理与人类开发者使用。
> 仓库内 `docs/` 下仍留有少量描述性 `.md`（见 §六），均已保留勿删；如发现本文与代码不一致，以代码为准并修正本文。

## 一、项目简介

RustedWarfareModStudio（应用名「铁锈工坊」，包名 `com.rwmodstudio`）是一款 Android 端《铁锈战争》（Rusted Warfare）MOD 开发工具，为 MOD 开发提供移动端中文开发环境。

核心能力：
- **INI 编辑**：sora-editor + TextMate 语法高亮、符号栏、查找替换、行尾灯泡（强制翻译）、彩虹括号
- **代码补全**：节/键/值补全、自定义补全表（原生表 + 用户表 + 附件表）、翻译库兜底
- **中英翻译**：翻译引擎（中英双向）、翻译查重、屏蔽词、文件翻译缓存
- **项目辅助**：文件管理、项目标签/资源/内存扫描、继承链解析、版本对比、坐标可视化、待办、本地配置导入/导出
- **数据管理**：RWmod 目录统一存储、验证码强制刷新机制

技术栈：Kotlin + Jetpack Compose（无 XML 布局）；sora-editor 0.24.6 + TextMate。

## 二、技术栈与构建

| 组件 | 版本 |
|---|---|
| Gradle（wrapper） | 8.14.5 |
| Android Gradle Plugin | 8.11.1 |
| Kotlin / Compose 编译器插件 | 2.2.0 |
| Compose BOM | 2024.06.00 |
| sora-editor BOM | 0.24.6（配合 `app/libs/jcodings-1.0.63-noreader.jar`） |
| kotlinx-serialization-json | 1.7.1 |
| kotlinx-coroutines-android | 1.8.1 |

环境要点（来自 `settings.gradle.kts` / `gradle.properties` / `app/build.gradle.kts`）：
- 需要 **JDK 17**；仓库自带 `tools/jdk/jdk-17.0.19+10`，本机构建时使用它。
- `settings.gradle.kts` 配置阿里云镜像（google/central/gradle-plugin/public），保留原始源作备用，**不要移除镜像**。
- `local.properties` 存放 SDK 路径、release 签名信息与 `DEFAULT_AI_API_KEY`（默认 AI 密钥，注入 BuildConfig；`.gitignore` 已忽略，**不得提交**，源码内不得出现明文密钥）。
- `gradle.properties`：`android.overridePathCheck=true`、`android.suppressUnsupportedCompileSdk=36`。
- 版本号在 `app/build.gradle.kts`：`versionCode = 27`、`versionName = "1.3.6"`（发布时必须递增，只增不减）。

常用命令（PowerShell）：
```powershell
$env:JAVA_HOME = "D:\ALSO2004\android-tool\RustedWarfareModStudio\tools\jdk\jdk-17.0.19+10"
.\gradlew.bat assembleDebug    # Debug（约 21MB，未启用 R8）
.\gradlew.bat assembleRelease  # Release（R8 + 资源压缩，约 4.3MB，对外发布用这个）
.\gradlew.bat installDebug
.\gradlew.bat test
```

## 三、目录结构

```
app/src/main/
├── AndroidManifest.xml       # 存储权限、VIEW intent（.ini/.template/.rwmod/.tmx/.zip/.replay）
├── assets/
│   ├── data/
│   │   ├── code_reference.json            # 代码参考库主表（补全/文档的唯一数据源）
│   │   ├── translation.txt                # 原生翻译库
│   │   ├── extra_translation_supplement.txt
│   │   ├── extra_completions.json
│   │   ├── snippets.json
│   │   ├── raw/sections/                  # 节定义 raw（节补全/过滤）
│   │   ├── raw/value/                     # 枚举值/布尔值 raw（值补全）
│   │   ├── raw/translation/zh-cn/         # 翻译映射（运行时翻译）
│   │   └── value/                         # 值补全数据
│   ├── tables/                            # jcodings 675 个 .bin 编码表（勿删，见 §五-3）
│   └── textmate/                          # 高亮主题 + ini/ini.tmLanguage.json
└── java/com/rwmodstudio/
    ├── MainActivity.kt       # 入口：权限、intent 打开文件、后台初始化（补全表/翻译/缓存预热）
    ├── RwModApplication.kt   # applicationScope（进程级 IO 协程）
    ├── core/                 # 核心逻辑（见下）
    ├── core/translation/     # 翻译引擎/代码参考库/查重/屏蔽词等
    ├── editor/               # sora-editor 封装、TextMate 初始化、INI 语言
    ├── feature/completion/   # 补全入口 + value/ 各值类型 Provider
    ├── feature/coord/        # 坐标可视化
    ├── ui/
    │   ├── components/       # 通用组件与对话框
    │   ├── screens/          # 页面（MainApp 为导航壳）
    │   └── theme/            # 主题/字体
    └── util/                 # IniImageReader、UriUtils、FileImportHelper
```

### core/ 关键模块

| 文件 | 职责 |
|---|---|
| `RwmodPaths.kt` | 路径管理器：所有运行时生成文件必须位于 `RWmod/` 下分类子目录 |
| `SettingsManager.kt` | 全局设置（文件版存储 `RWmod/config/settings.json`）+ 启动迁移 |
| `VerifyManager.kt` | 统一验证码：集中存 `RWmod/config/verify.json`，改常量即强制刷新对应数据 |
| `SaveHistoryManager.kt` | 保存历史（近期修改、差异确认/回退） |
| `VersionComparator.kt` | 版本对比引擎（LCS 逐行 diff，.ini/.template） |
| `InheritanceResolver.kt` / `InheritanceCache.kt` | 文件继承链解析（复制与/模板/`@copyFromSection`）+ mtime 增量缓存 |
| `ProjectTagScanner.kt` | 扫描项目内标签/全局标签/资源/内存/单位名 |
| `DiffUtil.kt` | `computeLineDiff` 行级 diff |
| `LocalConfigManager.kt` | 本地配置 zip 导入/导出 |
| `TaskProgressManager.kt` | Debug 模式右上角后台任务进度浮窗 |
| `UpdateChecker.kt` / `UpdateDownloader.kt` | 检查更新（GitHub Releases，直连失败自动切 `gh-proxy` 镜像）/ APK 下载 |
| `FileSettings.kt` / `ThemeState.kt` / `ThemeManager.kt` / `DarkThemeColors.kt` / `TodoManager.kt` | 文件键值存储 / 主题 / 待办 |

### core/translation/ 关键模块

| 文件 | 职责 |
|---|---|
| `TranslationEngine.kt` | 翻译引擎单例（`getInstance()`）：加载字典/代码参考库/片段、中英互译、`@define/global` |
| `TranslationDict.kt` | 翻译字典（原生 + 用户） |
| `CodeReferenceRepository.kt` | 代码参考库（`code_reference.json`）加载与查询 |
| `TranslationBlocklist.kt` | 屏蔽词（行尾灯泡据此显示） |
| `TranslationDedupChecker.kt` | 翻译查重 |
| `SearchTranslationCache.kt` | 文件翻译缓存（增量、mtime 判定） |
| `BehaviorVerifier.kt` / `ProjectRegistry.kt` | 危险行为确认 / 项目注册 |
| `SnippetRepository.kt` | 代码片段 |
| `CompletionShared.kt` | 补全统一判定（单位生成/单位名/单位标记/复制节等），供 Provider 与分类页共用 |
| `MockIniGenerator.kt` / `CompletionTypeRepository.kt` | 模拟 ini 生成 / 补全类型（真实判定，`CompletionTypeRepository` 已并入真实判定） |
| `ResourceNameKeys.kt` | 资源名属性键（mutator 变体归一化） |

## 四、关键约定（改动前必读，均来自当前代码）

### 1. 文件路径：一切生成文件只能在 RWmod 目录内

- 运行时生成/写出的文件必须位于 `RWmod/` 下的分类子目录（`translation/`、`completions/`、`config/`、`cache/`、`dedup/`、`todos/`、`imports/`、`exports/`）。
- 禁止写入 `filesDir`、`cacheDir`、应用私有目录或 `RWmod/` 根目录。
- 路径一律通过 `RwmodPaths` 获取，不要硬编码。旧版散落文件由 `SettingsManager.migrateFileStorage()` 启动时一次性迁移。

### 2. 验证码机制（VerifyManager）

- 生成文件/配置的验证码集中存放在 `RWmod/config/verify.json`。
- 修改 `VerifyManager.kt` 中对应 `xxx_CODE` 常量，下次启动即强制重置/重新生成对应数据（补全表、代码参考库、翻译缓存等）。
- 新增需要「强制更新」能力的生成文件，必须走这套机制。

### 3. 代码参考库与补全表数据流

- `code_reference.json` 是代码参考的唯一数据源，通过 `CodeReferenceRepository` 加载（优先读 `RWmod/cache/code_reference.json` 运行时副本，缺失时回退 assets，并用验证码控制重拷）。
- 加载时：`sections` 的 key 进 `realSectionNames`；`values` 中**带 type** 的分类进 `sectionProperties`，**不带 type** 的进 `valueCategories`。
- 两个 API 用途不同：`getAllSectionNames()` 含 value 类别名（供全局搜索/遍历），`getRealSectionNames()` 仅真正节名（供 UI 分类展示/筛选）。UI 展示分类时用后者。
- 自定义补全表（`CustomCompletion`，定义于 `CustomCompletionsScreen.kt`）：
  - `category: List<String>`（多分类，兼容旧版字符串自动转单元素列表）
  - `formatCategory`：`"属性"` → 插入 `$name:$value`；`"values"` → 插入 value；`"特定值"` → 插入 name
- `generateNativeItems(engine)` 用 `engine.getCodeReference()` + `engine.getTranslationDict()` 生成原生表；验证码匹配时直接返回已存表，否则重新生成并经 `translateCompletionsToChinese()` 翻译后保存。

### 4. 补全行为（CompletionProvider.kt）

- 节名映射：`sectionEnToZh`（18 个核心节：核心/图像/AI/攻击/运动/行动/隐藏行动/效果/动画/附属/可建造/贴花/资源/全局资源/炮塔/抛射体/腿/放置规则），支持 `global_resource_xxx`、`核心_1` 等归一化（`mapSectionName`）。
- 值补全：光标在 `:` 后时触发，只替换当前片段（以 `,`/`(`/空白为分隔），经 `ValueCompletionAggregator` 聚合各 Provider（布尔/枚举/图片/内存/资源/标签/单位名/产生单位等）。
- 翻译库兜底：遍历 `allDictKeys`（字典英+中 key），**跳过**自定义补全表（`mergedCustom` 按 label 去重、用户表优先）中已存在的 `name`/`nameEn`/`label`，也跳过结果中已加入的项，命中项插入 `category=["翻译库"]` 的 KEY 补全。
- 排序：值补全 > 非 values/特定值 > values/特定值；用户表项置顶。

### 5. 节名解析（EditorScreen.kt）

- 取光标所在行之前（含当前行）最后一个 `[节名]`，由 `parseSectionsImmediate()` 解析。
- 文件加载完成立即解析一次；`text`/`cursorPos` 变化经 `snapshotFlow { text to cursorPos }.debounce(250)` 后解析。
- `LaunchedEffect` **必须用 `filePath` 作 key**，否则切换文件后仍观察旧状态。
- 相关开关（`SettingsManager`）：`devSectionParsing`（是否解析）、`devSectionBar`（是否显示节名栏）。

### 6. 保存安全（EditorScreen.saveSync）

- `saveSync(caller)` 是挂起函数，内部 `withContext(Dispatchers.IO)` 写盘。
- 保存内容**优先读编辑器实际内容**（`latestEditorText`，由 `onTextChange` 同步维护，非线程安全时再用 `text` 兜底），避免状态同步延迟保存旧内容；仅在 `isEnglish && showChinese` 时先反向翻译（`engine.translateToEnglish`）。
- 保存前快照 `targetPath`，写入后校验 `targetPath == filePath`（路径变化打警告）。
- 所有保存调用带 `caller` 标记（BackHandler/SaveAndExit/LifecycleOnPause/TabSwitch/MenuSave/BackConfirm）。
- 每次保存记录到 `SaveHistoryManager`（`RWmod/config/save_history.json`，上限由 `SettingsManager.recentHistoryLimit` 控制）。
- 大文件 I/O 一律放 `Dispatchers.IO`。

### 7. UI 与导航（MainApp.kt）

- 导航：`ModalNavigationDrawer`（侧边栏）+ `Scaffold` 全局 `topBar`。**所有页面自带全局标题栏，不要再额外加标题栏**。
- 返回：`backStack` 记录嵌套导航，"从哪个页面打开，就返回哪个页面"（`pushScreen`/`popScreen`/`clearBackStack`）。
- 页面枚举 `Screen`：HOME / BROWSER / EDITOR / CODE_REF / SETTINGS / TRANSLATION / CUSTOM / DEVELOPER / COMPLETION_TYPES / COORD_VISUAL / VERSION_COMPARE / PROJECT_MANAGER / TODO_LIST。
- 关键状态用 `rememberSaveable`（`selectedFile`、`currentScreen`、`saveAndExit` 等）防止配置变更丢失。
- 编辑器工作区（`workspace` 内存缓冲，含文本/光标/滚动/修改标记）与存活实例仅在编辑器内存活，离开编辑器按三口径处理（`onBack(discard, saveAll)` + 离场统一效应）：**明确保存**（返回确认框「保存」/保存并退出）→ 全部实例落盘并销毁工作区；**明确不保存** → 丢弃不落盘并销毁；**直接返回**（返回键无修改、抽屉/工具页一切未做选择的离开）→ 自动保存开启时全部落盘并销毁，未开启时暂存（`holdPaths`，不落盘，下次打开恢复）。

### 8. 主题三件套（彼此独立，不能互相覆盖）

- 主题色（全局深/浅）→ `ThemeState.isDark`
- 编辑器背景色（仅编辑器背景）→ `ThemeState.bgColor`
- 代码高亮主题（TextMate：vs-dark/light/pure/neon/monokai/github）→ `ThemeState.highlightTheme`
- 状态栏/导航栏颜色：编辑器页与编辑器配色一致，其他页跟随主题色（`MainApp` 统一处理）。

### 9. 项目硬性要求（docs/项目要求.txt，保留的需求定义）

- 返回逻辑：从哪个页面打开，返回到哪个页面（见约定 7）。
- 页面已有全局标题栏，不额外新增。
- 生成文件不出 `RWmod` 目录（见约定 1）。
- 生成文件配验证码（见约定 2）。
- 代码参考表仅参与本地自定义补全表原生表的生成（见约定 3）。

## 五、常见陷阱（代码观察）

0. **调试设备问题先查包名（血泪教训，2026-09-01 踩过）**：App 有两个 applicationId——正式版 `com.rwmodstudio`、debug 变体 `com.rwmodstudio.debug`（`app/build.gradle.kts` 里 `applicationIdSuffix = ".debug"`）。**用户说"装的是测试版"时，指的就是 debug 包**。`adb shell pm list packages | grep rwmod` 会看到两个包；查安装时间/版本时用 `adb shell dumpsys package com.rwmodstudio.debug`，**不要只查正式包**。判断"设备上是不是最新代码"，以 **debug 包的 lastUpdateTime ≥ APK 构建时间** 为准，别拿正式包的时间戳下结论。
1. **org.json `optString` 双坑（AiService 流式解析，2026-09-01 血泪）**：`optString("content")` 在两种情况都返回字符串 `"null"`——① key 缺失；② key 存在但值为 JSON `null`（`{"content":null}`）。**`has("content")` 只能挡①，挡不住②**：`has()` 对 JSON null 返回 true，`optString` 照样吐出 `"null"` 且非空，会通过 `isNullOrEmpty()` 检查被 append 进输出 → 界面一直显示 null。修复必须三重过滤：`delta.has("content")` + `optString` 结果 `isEmpty()` + `== "null"` 再排除（见 `AiService.postChatCompletionStream`）。同样 `extractMessageContent` 用 `takeIf { it.isNotEmpty() && it != "null" }`。
2. **UI 分类混入 value 类别名**（如 `bool`、`Prices_Resources`、`drawLayer`）：说明误用了 `getAllSectionNames()`，改回 `getRealSectionNames()`。
2. **切换文件后节名不更新**：`LaunchedEffect` 忘了用 `filePath` 作 key。
3. **删除 `assets/tables/`**：675 个 `.bin` 表由 `org.jcodings.util.ArrayReader` 运行时加载，`packaging.resources.excludes` 对 assets 不生效；删表会导致 sora-editor/TextMate 初始化或正则崩溃。
4. **修改 `applicationId`**：等于发布新应用，已安装用户数据丢失。
5. **保存丢内容**：异步保存未等待、保存前未快照路径、英文视图保存了过期文本（当前 `saveSync` 已规避，改动时保持）。
6. **并发写 JSON**（翻译库/补全表）：快速连点可能写坏文件；参考 `TranslationEngine` 的 `Mutex` 写法。
7. **新增需要强刷的数据不走 `VerifyManager`**：老用户将永远用旧数据。
8. **当前已知性能点**（改动时注意）：
   - 图片解码已统一走 `IniImageReader.decodeSampled`（先 `inJustDecodeBounds` 算 `inSampleSize`，1024px 上限），在 `Dispatchers.IO` 执行；HomeScreen 首页目录预览也复用此采样解码。**改动时勿再直接 `BitmapFactory.decodeFile` 整图解码大贴图**。
   - 启动阶段：`SettingsManager.migrateFileStorage`（旧文件递归迁移）已移入 `RwModApplication.applicationScope` 后台 IO 执行，不阻塞启动主线程；但迁移只影响「按路径惰性读取」的文件，改动 `init()` 顺序时注意不要重新引入主线程同步迁移。
   - 说明：HomeScreen 打包/解压 `.rwmod` 已在 `scope.launch(Dispatchers.IO)` 中执行；近期修改的 Diff 计算在 `Dispatchers.Default`、SaveHistoryManager 读写在 IO 线程，均已避免卡主线程。

## 六、其他文档

| 文档 | 内容 |
|---|---|
| `docs/项目要求.txt` | 项目硬性要求（需求定义，勿删） |
| `docs/CodeWiki.md` | 代码中文技术参考 Wiki（较新，以此为准） |
| `docs/补全验证清单.md` / `docs/补全对照核对报告.md` | 补全功能验证/核对记录（可能过期，以代码为准） |
| `docs/优化计划.md` | 优化计划与执行状态（部分可能过期，以代码为准） |
| `计划表.md` | 功能迭代计划与阶段划分（部分可能过期，以代码为准） |
| `README.md` | 项目简介（开发状态部分可能过期，以代码为准） |

> `docs/` 下现存 4 个描述性 `.md`（CodeWiki/补全验证清单/补全对照核对报告/优化计划）均已保留，勿删；`icon_source.jpg` 是 `generate_icons.py` 的图标源图（资源，勿删）。
