# 铁锈工坊（RustedWarfareModStudio）代码 Wiki

> 本文档基于当前仓库源码整理，是项目的中文技术参考。若与代码不一致，以代码为准。
> 项目硬性要求见 `docs/项目要求.txt`，代理开发约定见根目录 `AGENTS.md`。

---

## 目录

1. [项目概览](#1-项目概览)
2. [技术栈与构建环境](#2-技术栈与构建环境)
3. [整体架构](#3-整体架构)
4. [目录结构](#4-目录结构)
5. [应用入口与生命周期](#5-应用入口与生命周期)
6. [核心逻辑模块 core/](#6-核心逻辑模块-core)
7. [翻译引擎模块 core/translation/](#7-翻译引擎模块-coretranslation)
8. [编辑器封装模块 editor/](#8-编辑器封装模块-editor)
9. [代码补全模块 feature/completion/](#9-代码补全模块-featurecompletion)
10. [坐标可视化模块 feature/coord/](#10-坐标可视化模块-featurecoord)
11. [UI 层 ui/](#11-ui-层-ui)
12. [工具类 util/](#12-工具类-util)
13. [数据与资源文件](#13-数据与资源文件)
14. [模块依赖与数据流](#14-模块依赖与数据流)
15. [运行与构建](#15-运行与构建)
16. [单元测试](#16-单元测试)
17. [关键约定与常见陷阱](#17-关键约定与常见陷阱)

---

## 1. 项目概览

**铁锈工坊**（英文 RustedWarfare Studio，包名 `com.rwmodstudio`）是一款 Android 端《铁锈战争》（Rusted Warfare）MOD 开发工具，为 MOD 开发者提供完整的移动端中文开发环境。

核心能力一览：

| 能力 | 说明 |
|---|---|
| **INI 编辑器** | 基于 sora-editor + TextMate 语法高亮；符号栏、查找替换、行尾灯泡（强制翻译）、彩虹括号 |
| **代码补全** | 节/键/值补全；自定义补全表（原生表 + 用户表 + 附件表）；翻译库兜底；逻辑表达式补全 |
| **中英翻译** | 翻译引擎（中英双向）、翻译查重、屏蔽词、文件翻译缓存 |
| **项目辅助** | 文件管理、项目标签/全局标签/资源/内存扫描、文件继承链解析、版本对比、坐标可视化、待办 |
| **数据管理** | 所有生成文件统一存放外部存储 `RWmod/` 分类目录；本地配置 zip 导入/导出；验证码强制刷新机制 |

当前版本：`versionCode = 11`、`versionName = "1.2.0-Release"`。

---

## 2. 技术栈与构建环境

| 组件 | 版本 |
|---|---|
| Gradle（wrapper） | 8.14.5 |
| Android Gradle Plugin | 8.11.1 |
| Kotlin / Compose 编译器插件 | 2.2.0 |
| Compose BOM | 2024.06.00 |
| sora-editor BOM | 0.24.6（配合 `app/libs/jcodings-1.0.63-noreader.jar`） |
| kotlinx-serialization-json | 1.7.1 |
| kotlinx-coroutines-android | 1.8.1 |
| JDK | 17（仓库自带 `tools/jdk/jdk-17.0.19+10`） |

环境要点：

- **SDK/签名**：`local.properties` 存放 SDK 路径与 release 签名信息（`.gitignore` 已忽略，不得提交）。
- **镜像**：`settings.gradle.kts` 配置阿里云镜像（google/central/gradle-plugin/public），保留原始源作备用，**不要移除镜像**。
- **编译级别**：`compileSdk = 36`、`minSdk = 26`（Android 8.0）、`targetSdk = 34`。
- **gradle.properties**：`android.overridePathCheck=true`、`android.suppressUnsupportedCompileSdk=36`。
- **Java 版本**：source/target 均 17，开启 `coreLibraryDesugaring`（sora-editor 依赖）。

---

## 3. 整体架构

项目是**单模块（`:app`）** Android 应用，无 XML 布局，全部 UI 用 Jetpack Compose 构建。分层如下：

```
┌────────────────────────────────────────────────────────────┐
│  UI 层  ui/                                                  │
│  MainApp(导航壳) + screens(页面) + components(组件) + theme   │
├────────────────────────────────────────────────────────────┤
│  Feature 层                                                 │
│  feature/completion 代码补全（CompletionProvider+16个Provider）│
│  feature/coord 坐标可视化（解析→求值→渲染）                   │
├────────────────────────────────────────────────────────────┤
│  领域/核心层  core/ + core/translation/                      │
│  路径/设置/验证码/保存历史/继承链/标签扫描/翻译引擎/代码参考库  │
├────────────────────────────────────────────────────────────┤
│  基础设施  editor/(sora-editor 封装)  util/(图片/URI/导入)     │
│  assets/(代码参考库/翻译库/补全 raw/编码表/TextMate)          │
└────────────────────────────────────────────────────────────┘
```

**关键设计原则**：

- **生成文件只能在 `RWmod/` 分类目录内**（由 `RwmodPaths` 统一管理，禁止写入 filesDir/cacheDir/RWmod 根目录）。
- **验证码强制刷新机制**（`VerifyManager`）：生成文件/配置附带验证码，修改代码常量即强制重置对应数据。
- **翻译引擎为数据中枢**：所有中文 label、节名/键名归一化、补全兜底都走翻译库，禁止硬编码中文。
- **编辑器实时状态**：`EditorScreen` 持有 Compose 状态，通过 `editorRef`（CodeEditor）驱动底层编辑器，`saveSync` 统一保存入口。

---

## 4. 目录结构

```
app/src/main/
├── AndroidManifest.xml       # 存储权限、VIEW intent（.ini/.template/.rwmod/.tmx/.zip/.replay）
├── assets/
│   ├── data/
│   │   ├── code_reference.json            # 代码参考库主表（补全/文档唯一数据源）
│   │   ├── translation.txt                # 原生翻译库
│   │   ├── extra_translation_supplement.txt
│   │   ├── extra_completions.json
│   │   ├── snippets.json                  # 代码片段
│   │   ├── param/logicboolean.json        # 函数参数表（函数参数值补全）
│   │   ├── raw/sections/                  # 节定义 raw（节补全/过滤）
│   │   ├── raw/value/                     # 枚举值/布尔值 raw（值补全）
│   │   ├── raw/translation/zh-cn/         # 翻译映射（运行时翻译）
│   │   └── value/                         # 值补全数据（含逻辑/产生单位等）
│   ├── tables/                            # jcodings 675 个 .bin 编码表（勿删，见 §17）
│   └── textmate/                          # 高亮主题 + ini/ini.tmLanguage.json
└── java/com/rwmodstudio/
    ├── MainActivity.kt       # 入口：权限、intent 打开文件、后台初始化
    ├── RwModApplication.kt   # applicationScope（进程级 IO 协程）
    ├── core/                 # 核心逻辑（路径/设置/验证码/保存历史/版本对比/继承链/标签扫描等）
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

---

## 5. 应用入口与生命周期

### 5.1 RwModApplication.kt

进程级 Application，提供跟随进程生命周期的协程作用域：

| 成员 | 说明 |
|---|---|
| `applicationScope` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，用于后台初始化/保存翻译库后刷新补全表等耗时任务 |
| `getInstance()` | 单例访问 |

### 5.2 MainActivity.kt

入口 Activity（`launchMode = singleTask`）。

**`onCreate` 流程**：
1. `SettingsManager.init(applicationContext)`（初始化路径/验证码/文件版设置/迁移）。
2. `TranslationEngine.getInstance().loadBlocklist(...)`。
3. `ArrayReader.init(...)`（jcodings 编码表初始化，必须在编辑器使用前）。
4. `setContent { RustedWarfareModStudioTheme { MainApp() } }`。
5. 根据验证码判定是否后台生成附件表/原生补全表/翻译缓存预热/继承链缓存预热（单协程串行，经 `TaskProgressManager` 展示进度）。
6. `requestStoragePermissions()`（首次启动由引导页统一处理）。
7. `processIntent(intent)`。

**`processIntent`（外部文件打开）**：根据扩展名分发：

| 扩展名 | 处理 |
|---|---|
| `.replay` | 复制到 `replayImportDir`（铁锈回放目录） |
| `.rwmod` | 复制到 `rwmodImportDir`（模组目录） |
| `.tmx` | 复制到 `mapImportDir`（地图目录） |
| `.ini` / `.template` | `file://` 直接取绝对路径；`content://` 先复制到 `RWmod/imports/ini` 再打开 |
| `.zip` | `LocalConfigManager.importFromZip` 导入本地配置 |

外部待打开文件通过 `companion object pendingOpenFile` 交给 `MainApp` 消费。

### 5.3 AndroidManifest.xml

- 权限：`READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`。
- `<queries>`：探测 `com.corrodinggames.rts`（铁锈战争本体，用于“启动游戏”按钮）。
- `FileProvider`：`${applicationId}.fileprovider`（`@xml/file_paths`）。
- VIEW intent 支持：`.ini` / `.template` / `.replay` / `.rwmod` / `.tmx` / `.zip`。

---

## 6. 核心逻辑模块 core/

### 6.1 RwmodPaths.kt — 路径管理器（object）

**职责**：统一管理 `RWmod/` 目录下所有运行时生成文件的路径，禁止硬编码路径。

**分类目录**（均为懒创建）：

| 属性 | 路径 | 用途 |
|---|---|---|
| `rwmodDir` | `外部存储/RWmod` | 工作根目录 |
| `translationDir` | `RWmod/translation` | 翻译库（用户表/原生表/附件表/缓存） |
| `completionsDir` | `RWmod/completions` | 补全表（原生/附件/用户/自定义） |
| `configDir` | `RWmod/config` | 设置/验证码/屏蔽词/节过滤/危险行为/项目注册/保存历史 |
| `cacheDir` | `RWmod/cache` | 代码参考库副本/文件翻译缓存/继承链缓存/配置导入临时区 |
| `dedupDir` | `RWmod/dedup` | 查重词 |
| `todosDir` | `RWmod/todos` | 待办 |
| `importsDir` | `RWmod/imports`（含 `ini` 子目录） | 外部导入 |
| `exportsDir` | `RWmod/exports` | 配置导出 |
| `migratedDir` | `RWmod/migrated` | 无法自动分类的旧文件迁移兜底 |

**关键文件常量**（节选）：

- 翻译：`userTranslationFile`、`nativeTranslationFile`、`extraTranslationFile`、`translationCacheFile`、`translationCacheMetaFile`、`translationCacheSourcesFile`。
- 补全：`nativeCompletionsFile`/`_en`、`extraCompletionsFile`/`_en`、`userCompletionsFile`/`_en`、`customCompletionsFile`。
- 配置：`settingsFile`、`verifyFile`、`translationBlocklistFile`、`sectionFiltersFile`、`verifiedBehaviorsFile`、`projectRegistryFile`、`saveHistoryFile`。
- 缓存：`codeReferenceFile`（assets 副本）、`fileTranslationCacheDir`/`data`/`index.json`、`inheritanceCacheDir`/`index.json`、`importConfigTempFile(timestamp)`。
- 兼容旧路径：`legacyAndroidRwmodRoot`（`Android/RWmod`）、`legacyDedupDir`（`RWmod/查重`）、`legacyTodosDir`（`RWmod/project_todos`）、`legacySearchTranslationDir`（已废弃，启动清理）。

### 6.2 FileSettings.kt — 文件键值存储

**职责**：`RWmod/config/settings.json` 的键值读写（SharedPreferences 的替代），单线程 executor 串行持久化。

| 函数 | 说明 |
|---|---|
| `create(context)` | 工厂方法（从 SharedPreferences 迁移旧数据） |
| `getString/getBoolean/getFloat/getInt/contains` | 读取 API |
| `edit(): Editor` | 链式编辑（putString/putBoolean/... + `apply()`） |

### 6.3 SettingsManager.kt — 全局设置单例（object）

**职责**：暴露应用所有设置的强类型属性（读写走 `FileSettings`），并在 `init()` 时执行一次性迁移。

**`init(context)` 流程**：
1. 初始化 `RwmodPaths.rwmodDir`。
2. `VerifyManager.init(context)`。
3. `settings = FileSettings.create(context)`。
4. `migrateFileStorage(context)`：把旧版散落在 `Android/RWmod`、`RWmod/` 根目录、`filesDir/data` 的文件/目录迁移到新分类目录（映射表见源码）。
5. `migrateDefaults()`：一次性默认值迁移（`dev_value_completion`→true、`auto_space`→false，带 migration 标记）。
6. `initLocalFiles()`：确保 `custom_completions.json` 存在。
7. `pruneRecentFiles()`：清理失效的最近文件条目。

**代表性设置项**（均为 `var` 属性）：

- 编辑器：`fontSize`（8~32）、`autoWrap`、`smartWrap`、`editorFontFamily`（system/system_mono/jetbrains_mono/lxgw_wenkai_regular）、`autoSpace`。
- 主题：`isDarkTheme`、`bgColor`、`highlightTheme`（dark/light/pure/custom）、`darkTokenColors`。
- 彩虹括号：`rainbowBrackets`、`bracketDiagnostics`、`wrapIndicatorEnabled`、`rainbowBracketIntensity`、`rainbowHueStep`、`rainbowHueDirection`、`rainbowSaturationBoost`、`rainbowLightnessShift`、`rainbowAutoLightnessDirection`、`rainbowVisibilityGuard`。
- 补全：`completionDetailEnabled`、`nonValueCompletionLimited`、`completionFilterSections`、`customCompletionsJson`、`customSymbolsJson`、`customCompletionFormats`、`customCategories`。
- 翻译：`customTranslations`、`autoRefreshCompletionsOnTranslationSave`、`completionTranslationRefreshCode`、`translationBlockEnabled`。
- 路径：`defaultPath`、`lastPath`、`recentFiles`（上限 20）、`replayImportDir`、`rwmodImportDir`、`mapImportDir`。
- 开发者子开关：`devMode`、`devCoordVisual`、`devCompletionProvider`、`devValueCompletion` 及 `devValueCompletion{Bool,LogicBoolean,Enum,Image,UnitSpawn,AutoTriggerOnEvent}`、`devSectionParsing`、`devTranslationEngine`、`devSectionBar`、`devSectionCompletion`、`devLightbulbEnabled`、`devInheritanceView`、`devSaveOnPause`、`devLineNumber`、`devTabBar`、`devShowCopyPath`、`devDebugTaskProgress`、`devFileLoading`、`devRecentFiles`。
- 其他：`recentHistoryLimit`（50~200）、`pinnedHomeItems`、`lastRecentDialogTab`、`onboardingVerified`。

**节过滤**：
- `DEFAULT_SECTION_FILTERS`：各节默认启用补全分类（如 `核心→{核心,values,特定值}`、`资源→{资源,全局资源,values,特定值}`）。
- `loadAllSectionFilters()` / `saveAllSectionFilters()` / `saveSectionFilter()` / `resetSectionFilters()`：存 `RWmod/config/section_filters.json`，带验证码校验（不匹配自动重置默认）。

**默认模组目录探测** `defaultModPath()`：依次探测 `rustedWarfare/units`、`Android/data/com.corrodinggames.rts/files/rustedWarfare/units`、`games/com.corrodinggames.rts/units`。

### 6.4 VerifyManager.kt — 统一验证码（object）

**职责**：所有生成文件/配置的验证码集中存 `RWmod/config/verify.json`。修改代码中 `xxx_CODE` 常量，下次启动即强制重置对应数据。

**验证码 key 与常量**：

| key | 常量 | 控制数据 |
|---|---|---|
| `BLOCKLIST` | `BLOCKLIST_CODE = "384757"` | 屏蔽词 |
| `SECTION_FILTERS` | `SECTION_FILTERS_CODE = "592047"` | 节过滤 |
| `NATIVE_COMPLETIONS` | `NATIVE_COMPLETIONS_CODE = "819411"` | 原生补全表 |
| `EXTRA_COMPLETIONS` | `EXTRA_COMPLETIONS_CODE = "530719"` | 附件补全表 |
| `CODE_REFERENCE` | `CODE_REFERENCE_CODE = "720522"` | 代码参考库 |
| `ONBOARDING` | `ONBOARDING_CODE = "294817"` | 首次引导 |
| `TRANSLATION_CACHE` | `"100001"` | 翻译缓存 |
| `COMPLETIONS` / `SETTINGS` / `NATIVE_TRANSLATION` / `EXTRA_TRANSLATION` | `"100002"`~`"100005"` | 补全/设置/原生翻译/附件翻译 |

**API**：`init()`（从 SharedPreferences / 旧 verify.json / verify.txt 迁移）、`read(key)`、`write(key, code)`、`resetAll()`、`isValid(key, expected)`。

### 6.5 SaveHistoryManager.kt — 保存历史（object）

**职责**：记录每次保存前后内容，支持“近期修改”展示、差异确认/回退。存 `RWmod/config/save_history.json`。

| 函数 | 说明 |
|---|---|
| `init(context)` | 加载索引 |
| `record(context, filePath, beforeContent, afterContent)` | 记录一次保存 |
| `getRecentFiles(context)` | 最近修改的文件列表 |
| `getHistoryForFile(context, filePath)` | 单文件历史记录 |
| `confirmFile(context, filePath)` | 用户确认（更新基线） |
| `updateBaseline(context, filePath, newBaseline)` | 更新基线内容 |
| `revertFile(context, filePath)` | 回退到基线 |

### 6.6 DiffUtil.kt — 行级 diff

- `data class DiffOp(type: Char, text, oldLine, newLine)`：type 为 `= `（相同）/`- `（删除）/`+ `（新增）。
- `fun computeLineDiff(before, after): List<DiffOp>`：DP（LCS）行级 diff，供近期修改/版本对比展示。

### 6.7 VersionComparator.kt — 版本对比（object）

**职责**：对比两个版本目录（`.ini`/`.template` 差异），用于版本对比页面。

| 函数 | 说明 |
|---|---|
| `getAllFiles(dir, baseDir)` | 递归收集文件相对路径集合 |
| `compareFolders(rootDir, metaDir)` | 计算文件夹差异结果（`FolderDiffResult`：新增/删除/修改文件，行级 diff） |

### 6.8 InheritanceResolver.kt / InheritanceCache.kt — 文件继承链

**职责**：解析文件的继承关系（`@copyFromSection` / 模板 / 复制节），合并出完整内容视图；带 mtime 增量缓存。

- `InheritanceResolver`（object）：
  - `resolveFormatted(filePath, projectRoot)`：格式化继承链文本。
  - `resolve(filePath, projectRoot): ResolvedInheritance?`：解析（`chainText` 继承链概览 + `mergedLines` 合并行，每行带 `SourcedLine` 来源标注）。
  - `resolveSymbols(filePath, projectRoot)`：解析继承链符号（供补全）。
  - `resolveMergedLines(...)`：内部核心，遍历 `@copyFromSection`/模板逐级合并，支持 `@copyFrom_skipThisSection`、节级复制展开。
- `InheritanceCache`（object）：缓存合并结果到 `RWmod/cache/inheritance/`（`index.json` + 数据文件），`isFresh` 基于源文件 mtime 签名判定，`prepareIfNeeded` 启动预热，`cleanStaleEntries` 清理过期。

### 6.9 ProjectTagScanner.kt — 项目符号扫描（object）

**职责**：扫描项目内标签/全局标签/资源/内存/单位名/命名节名，提供补全与项目管理页的数据源。维护静态 `cachedInfo`（同 root 只扫一次）。

**数据结构**：`ProjectTagInfo`（tags/globalTags/messageTags/resources/globalResources/memories/unitNames/sectionNames/memoryTypes/globalVariables/sectionDefines/references）。

| 函数 | 说明 |
|---|---|
| `scan(root)` / `scanIfNeeded(root)` / `getCachedInfo()` | 扫描/按需扫描/读取缓存 |
| `scanChainLines(lines)` | 从继承链合并行扫描 |
| `parseDefineUnitMemory(value)` / `parseDefineUnitMemoryTyped(value)` | 解析 `defineUnitMemory: 类型 名, ...` |
| `parseNamedSectionLine(line)` | 解析命名节（炮塔/效果/行动等）基名+名字 |

### 6.10 LocalConfigManager.kt — 本地配置导入/导出（object）

- `exportToZip(context)`：把 `RWmod/` 下配置文件打包成 zip（`RWmod/exports/config_export.zip`）。
- `importFromZip(context, zipFile)`：解压导入配置。
- `shareZip(context, zipFile)` / `findConfigZipFiles(context)`。

### 6.11 TaskProgressManager.kt — 后台任务进度（object）

Debug 模式右上角后台任务进度浮窗。`start(name, total)` / `update(current, detail)` / `finish()`；`currentTask`（当前任务）、`completedTasks`（已完成列表）、`displayActive`。

### 6.12 主题三件套

- **ThemeState.kt**（object）：Compose 状态，`isDark`（深/浅）、`bgColor`（编辑器背景）、`highlightTheme`（TextMate 主题）、`darkTokenColors`；`toggle()`/`applyBgColor()`/`applyHighlightTheme()`/`applyDarkTokenColors()`。
- **ThemeManager.kt**（object）：`bgColor`、`getAdjustedRustedBackground()`、`getAdjustedRustedSurface()`（随编辑器背景色调整全局配色）。
- **DarkThemeColors.kt**：`DarkThemeColors` 数据类（ui/plainText/section/keyword/control/string/number/boolean/function/parameter/bracket/memory/... 等 token 颜色）及 `Default` 实例、`toJson()`/`fromJson()` 序列化。

### 6.13 TodoManager.kt — 待办（object）

- `load(projectPath)` / `save(projectPath, items)` / `add(projectPath, title, priority, note)` / `update` / `delete` / `deleteCompleted`。数据存 `RWmod/todos/`。

---

## 7. 翻译引擎模块 core/translation/

### 7.1 TranslationDict.kt — 翻译词典

**职责**：中英翻译词库。由三个来源合并：**用户表**（`RWmod/translation/user_translation.json`，旧版兼容 `translation.txt`）> **附件表**（`extra_translation.txt`，由 `extra_completions.json` + `extra_translation_supplement.txt` 生成）> **原生表**（`native_translation.txt`，assets 副本）。合并结果缓存到 `translation_cache.txt`，源文件 hash 变化时重建（`CACHE_VERSION = 8`）。

**核心数据结构**：
- `enToZh` / `zhToEn`：键名中英映射。
- `sectionEnToZh` / `sectionZhToEn`：节名映射。
- `valueTranslations`：特殊值/布尔值双向映射（`specialValues` 如 true/false/LAND/WATER/HOVER/AIR/...）。
- `entrySources`：每条目来源（NATIVE/EXTRA/USER）。
- `logicBooleanPrefixes`：逻辑布尔值前缀集合（`self.hp`、`memory.`、`select(` 等，用于值补全/翻译识别）。

**关键函数**：
- `getTranslation(enKey)`：英文→中文（含 `self.xxx()` 归一化：**先查含 self 的完整键**，失败再查裸名；`xxx()` 去括号回退）。
- `getTranslationBack(zhKey)`：中文→英文。
- `getValueTranslation(enValue)` / `getValueTranslationBack(zhValue)`：值翻译（`resolveValueTranslation`，只剥空括号 `()`，带参括号不剥，返回库样式裸名）。
- `getSectionTranslation` / `getSectionTranslationBack`：节名映射。
- `translateInText(text, isEnToZh)`：用预编译统一正则对文本内单词做翻译（`\b` 边界）。
- `isChineseInLibrary` / `translateChineseToEnglish` / `getAllEntries` / `getAllEntriesWithSource` / `getSource`。
- 编辑类：`saveToExternal`（写用户表）、`updateEntry` / `deleteEntry`（按来源增删改，更新内存+清缓存）、`resetToDefault`。

### 7.2 TranslationEngine.kt — 翻译引擎（单例）

**职责**：翻译功能中枢，协调词典/代码参考库/片段/屏蔽词，提供整文件与单行中英互译。

**加载**：`load(context)`（幂等，`Mutex` 保护）：加载屏蔽词 → 词典 → `ensureCodeReferenceGenerated` → 代码参考库 → 片段。

**`ensureCodeReferenceGenerated`**：验证码不匹配或 `RWmod/cache/code_reference.json` 缺失时，把 `assets/data/code_reference.json`（中文主表）复制到 files 目录，并写入验证码。

**翻译 API**：
- `translateToChinese(englishText, autoSpace)` / `translateToEnglish(chineseText, autoSpace)`：整文件翻译。
- `translateToEnglish(chineseText, autoSpace, forcedLineIndices)`：指定行强制翻译（跳过屏蔽词）。
- `translateLineToChineseForce(line, autoSpace)`：单行强制翻译（行尾灯泡用）。
- `isEnglishIni(text)`：判定文件是英文还是中文视图（统计节/键翻译命中计数）。

**内部行处理流程**（`processLines`）：
1. 空行/注释（`#`/`;`）原样保留。
2. `[section]` → 节名翻译（`global_resource_` ↔ `全局资源_` 前缀特判，`_` 拆前缀翻译）。
3. `@define/@global name:value` → 只翻译 value。
4. 键值行（`key:value`）：`translateKVToChinese/English`。
   - 键被屏蔽（`shouldBlockKey`）时 value 整体不译；若开启 `forcePercentVariables` 仅译 `%{...}` 内部。
   - value 翻译：`translateValue` 先按顶层逗号切分（跳过括号内逗号），逐段 `translateSingleValue`：先译 `%{...}`，再处理布尔/特殊值，`protectQuotedDictWords` 保护引号内词典词，`protectFragments` 保护 `${...}` 与 `@token`，`translateInText` 翻译，再恢复占位符，最后处理内嵌布尔 token（如 spawnUnits 参数 `gridAlign=true`）。
5. 多行字符串 `key:"""..."""`：收集完整内容后整体翻译，按原始行结构还原。
6. 未匹配行 → `translateInText` 文本级翻译。

**其他 API**：
- 屏蔽词管理：`loadBlocklist` / `resetBlocklist` / `setBlocklistEnabled` / `updateBlocklistKeys` / `updateBlocklistFlags` / `getBlocklist` / `getBlockableStringKeys(typeLookup)`（行尾灯泡可强制翻译的 string 类型 key 集合）。
- `getCompletionProvider(customCompletions, nativeCompletions, showDetail, valueSectionProperties)`：工厂方法，构造带值补全选项的 `CompletionProvider`。
- `getCodeReference()` / `getTranslationDict()` / `getAppContext()`。
- `translateCompletionItems(items)`：批量翻译补全项 label/detail/insertText。
- `stats`：翻译统计（`TranslationStats`）。

### 7.3 CodeReferenceRepository.kt — 代码参考库

**职责**：加载 `code_reference.json`，提供属性文档与补全数据。

**数据结构**：
- `PropertyInfo`：`name`、`type`、`description`、`version`、`isOutdated`、`example`、`name_en`、`desc_zh`、`default`（清洗后的默认补全值）。
- `CodeReference`：`sections: Map<String, SectionData>` + `values: Map<String, ValueCategory>`。

**加载分流**（`loadFromAssets`）：
- `sections` 的 key → `sectionProperties` + `realSectionNames`（**真正的节名**）。
- `values` 中**带 type** 的分类 → `sectionProperties`（属性类别）；**不带 type** 的 → `valueCategories`（值类别）。
- 数据源：优先 `RWmod/cache/code_reference.json` 运行时副本，缺失回退 assets。

**API**：
- `getPropertiesForSection(sectionName)`、`searchProperties(query, sectionName?)`、`getPropertyDocumentation(property)`。
- `getAllSectionNames()`（含 value 类别，供全局搜索/遍历）、`getRealSectionNames()`（仅真节名，供 UI 分类展示/筛选）、`getAllValueCategoryNames()`、`getValueCategory(name)`、`isRealSection(name)`。

> ⚠️ 注意：UI 展示分类时**必须**用 `getRealSectionNames()`，误用 `getAllSectionNames()` 会把 bool/Prices_Resources 等类别名混入（AGENTS.md 常见陷阱 1）。

### 7.4 TranslationBlocklist.kt — 屏蔽词

`data class TranslationBlocklist(enabled, keys, blockVariables, blockAtTokens, blockFileNames, blockQuotedDictWords, forcePercentVariables, verifyCode)`。
- `load(context)` / `save(context, blocklist)`：读写 `RWmod/config/translation_blocklist.json`。
- `shouldBlockKey(key)`、`protectFragments(text)`（保护 `${...}`/`@token`）、`protectQuotedDictWords(text, isDictWord)`、`restoreProtected(text, placeholders)`。

### 7.5 TranslationDedupChecker.kt — 翻译查重

`checkProjectFiles(engine, folderPath, progress)`：扫描项目文件与翻译库重复的内容，返回 `DuplicateInfo` 列表；结果词写入 `RWmod/dedup/dedup_words.txt`。

### 7.6 SearchTranslationCache.kt — 文件翻译缓存（object）

**职责**：为每个 INI/template 文件缓存中文翻译副本，供搜索/编辑免翻译（增量、mtime 判定）。

- 缓存位置：`RWmod/cache/file_translation/`（`data/` + `index.json`），内存 LruCache 8MB。
- `isFresh(file)`（基于文件 mtime 与索引）、`readCacheSync(file)`、`putCacheSync(file, chineseContent)`、`getChineseContent(file)`（未命中时读原文+翻译+缓存）、`prepareIfNeeded(projectRoot, context)`（启动预热）、`cleanStaleEntries()`、`clear()`。

### 7.7 BehaviorVerifier.kt — 危险行为确认（object）

存 `RWmod/config/verified_behaviors.json`。`getVerifiedTypes(context)` / `isVerified(context, type)` / `markVerified(context, type)`。

### 7.8 ProjectRegistry.kt — 项目注册（object）

存 `RWmod/config/project_registry.txt`。`getRegisteredProjects()` / `isProjectRegistered(name)` / `registerProject(name)` / `getRegistryPath()`。首页打开项目前先做查重，通过后才注册。

### 7.9 SnippetRepository.kt — 代码片段

加载 `assets/data/snippets.json`。`load(context)`、`search(prefix)` → `SnippetResult`。

---

## 8. 编辑器封装模块 editor/

### 8.1 SoraCodeEditor.kt — 编辑器主封装

`@Composable fun SoraCodeEditor(...)`：封装 sora-editor 的 `CodeEditor`，集成 TextMate 高亮、行号、自动换行、智能换行、符号栏（`SymbolInputView`）、行尾灯泡（lightbulb）、撤销/重做、查找替换（`EditorSearcher`）。

- `enum class EditorToolbarAction`：工具栏动作枚举。
- `applyEditorTheme(...)` / `resolveEditorBackground(bgColor, isDarkTheme)` / `themeBackgroundColor(isDarkTheme)`：主题应用。
- 行尾灯泡：`createLightbulbDrawable` / `createFallbackBulbDrawable`，通过 sora 扩展 API 在行尾显示灯泡（点击强制翻译该行 value）。
- `parseLineKey(line)`：解析行键名（用于灯泡目标判定）。

### 8.2 IniLanguage.kt — INI 语言定义

- `class IniLanguage(private val delegate: TextMateLanguage) : EmptyLanguage()`：包装 TextMate 语言，支持 `getAutoIndent`、符号列表等。
- `class BracketDiagnosticManager(...)`：括号诊断（未匹配括号标记），配合 `bracketDiagnostics` 设置。

### 8.3 SoraEditorInitializer.kt — 初始化（object）

`SoraEditorInitializer`：初始化 TextMate 语言/语法高亮（加载 `assets/textmate/` 的 theme 与 `ini.tmLanguage.json`），读取文本时注册 `ArrayReader`。

### 8.4 ReadOnlyCodeEditor.kt — 只读编辑器

`@Composable fun ReadOnlyCodeEditor(...)`：只读代码展示（版本对比/继承链查看等场景），禁写。

### 8.5 SmartWrapBreaks.kt — 智能换行

`object SmartWrapBreaks` + `class PriorityBreaks`：按优先级在括号/逗号/运算符/空白处断行，`wrap(text, width)` 等。

### 8.6 RainbowColorUtils.kt — 彩虹括号配色（object）

`generateRainbowColors(...)`、`resolvePreviewBaseColor(highlightTheme)` 等：按嵌套深度生成彩虹色，支持强度/色相步进/方向/饱和度/亮度/可见性保护参数。

---

## 9. 代码补全模块 feature/completion/

### 9.1 CompletionProvider.kt — 补全总入口

**职责**：聚合自定义补全表（原生+用户）、值补全聚合器、翻译库兜底，输出最终补全列表。

**数据结构**：
- `CompletionItem`：`label`、`type`（SECTION/KEY/VALUE/TEMPLATE）、`detail`、`insertText`、`category: List<String>`、`valuePrefixLength`、`isUserCompletion`、`name`/`nameEn`（翻译库兜底去重用）、`valueType`（补全查看器分组）、`isCallable`（调用方/被调用方）。
- `sectionEnToZh`（internal val）：18 个核心节英文→中文映射（core→核心、graphics→图像、ai→AI、attack→攻击、movement→运动、action→行动、hiddenaction→隐藏行动、effect→效果、animation→动画、attachment→附属、canbuild→可建造、decal→贴花、resource→资源、global_resource→全局资源、turret→炮塔、projectile→抛射体、leg→腿、placementrule→放置规则）。

**核心入口** `getCompletions(textBeforeCursor, textAfterCursor, cursorPosition, currentSectionName, sectionFilters, sectionCompletionEnabled)`：

流程：
1. 提取光标前可替换前缀 `rawPrefix`（遇换行/空格/触发符停止）。
2. 值补全（`valueCompletionEnabled` 时）：取当前行 → 逻辑值入口抑制/关键字后抑制/运算符条件 → `splitKeyValueLine` 取 `:` 前的键 → `replaceableValuePrefix` 计算可替换片段 → 从 `ValueCompletionAggregator` 取候选（传入内存/标签/资源/单位名/节名/声音等符号）。
3. `${` 插值早退：只显示变量补全。
4. 值片段早退（`shouldReturnValueOnlyForValueFragment`）：值补全有结果且（空前缀或带点）→ 只返回值补全。
5. 括号内早退（`shouldReturnValueOnlyInParens`）：参数函数命中或空前缀 → 只显示值结果。
6. 空值未知键兜底抑制（`shouldSuppressEmptyValueFallback`）。
7. `[` 节名补全 → `getSectionCompletions`。
8. 节内空行节补全 → `getSectionBodyCompletions`（按 `sectionFilters` 分类过滤）。
9. `@memory` 定义补全（变量名阶段 / 类型阶段）。
10. `@` 指令补全（@global/@define/@memory）。
11. 自定义补全表前缀匹配（`mergedCustom` = 用户表 + 原生表去重，用户表优先）。
12. 翻译库兜底（`allDictKeys` 前缀匹配，跳过补全表已有项；值上下文插裸词，键位置插 `键:`）。
13. 过滤/排序管线：`dedupeValueByKeyPriority` → `orderByValueCollision` → `filterLogicEntryTokens` → `filterLogicSyntaxItems` → `filterStartersAfterValue` → `filterOperatorItems` → `sortCompletions`。

**辅助 internal 函数**（供补全查看器复用）：
- `splitKeyValueLine`、`replaceableValuePrefix`。
- 逻辑过滤：`LOGIC_ENTRY_TOKENS`（if/真/假/true/false）、`OPERATOR_ITEMS`（+ - * / < > <= >= == != %）、`LOGIC_SYNTAX_ITEMS`、`LOGIC_STARTERS`、`LOGIC_KEYWORDS`（if/and/or/not）、`LOGIC_CONNECTORS`（and/or）、`LOGIC_BOOLEAN_VALUES`。
- `valueEndsWithLogicKeyword`、`hasCompleteValueBeforeCursor`、`logicValueHasEntryToken`。
- `shouldReturnValueOnlyInParens`、`shouldSuppressEmptyValueFallback`、`shouldReturnValueOnlyForValueFragment`、`shouldBridgeToTranslationLibrary`、`dedupeValueByKeyPriority`、`orderByValueCollision`、`filterValueContextItems`。
- 符号提取：`extractCurrentFileSymbols(fullText, sectionToEnglish, keyToEnglish)`（内存/标签/资源/单位名/命名节名/全局变量/局部变量）、`extractFileSymbols`（合并项目级缓存）、`mergeCompletionSymbols`（合并继承链）。

### 9.2 value/ 值补全子包

#### ValueCompletionRequest.kt

一次值补全的完整上下文数据类（字段见 §9 列表），含便捷方法：
- `findProperty()`：**在当前节/全局节中查找属性定义**。中文属性名先经 `translationDict.getTranslationBack` 反查英文名再匹配（修复大量 name 为英文的条目漏匹配问题）。
- `toEnglishName()`：中文属性名→英文。
- `chineseNameMatchesEnglish(targetEn)`、`isChineseName()`、`isInsideParentheses()`。
- 顶层 `isInsideParentheses(textBeforeCursor)`：从行内第一个 `:` 后向前扫描未闭合 `(`。

#### BaseValueCompletionProvider.kt — 基类

抽象类：子类实现 `canProvide(request)` 与 `provideItems(request)`。
- `provideCompletionItems(request)`：`canProvide` 通过才调用。
- `provideFullItems(request)`：绕过 `canProvide` 直接 `provideItems`（补全查看器「+」菜单同源产出）。
- `createValueItem(label, detail, insertText, prefixLength, valueType, isCallable)`：构造 VALUE 类型补全项。

#### ValueCompletionAggregator.kt — 聚合器

**职责**：按 VS Code 插件架构注册多个 Provider，依次调用并合并结果。

- `ValueCompletionOptions`：各 Provider 开关（bool/logicBoolean/enum/image/unitSpawn/autoTriggerOnEvent/memory/tag/resource/unitName/unitRef/functionParam/projectRef/copyFromSection/defineVariable/crossSectionRef）。
- `init` 中按开关注册 16 个 Provider。
- `getValueCompletions(...)` → 摊平结果。
- `getValueCompletionsGrouped(...)` → 按 Provider 分组（补全查看器用），`ProviderResult(providerLabel, items)`。
- `getAllCallableItems(context, translationDict, memoryNames, memoryTypes)`：全量可调用条目，仅逻辑类 Provider（LogicBoolean/UnitRef/Memory）走 `provideFullItems`。
- `providerLabel`：Provider 类名→中文标签（布尔值/逻辑表达式/枚举值/图片路径/单位生成/事件触发/内存变量/标签/资源/单位类型/单位标记/函数参数/项目引用/复制节/变量定义/跨节引用）。

#### 各值类型 Provider（16 个）

| Provider | 触发条件 | 数据源/产出 |
|---|---|---|
| **BoolValueCompletionProvider**（布尔值） | 括号内抑制；属性 type 为 bool/boolean（`isBoolValueType`），或 `@copyFrom_skipThisSection` 名称启发 | `raw/value/bool.json`（true/false → 真/假，经 `translateValueName` 翻译，兜底英文） |
| **LogicBooleanValueCompletionProvider**（逻辑表达式） | 属性 type 为 logicboolean / logicnumber / logic / dynamic resources（`isLogicBooleanValueType`/`isLogicNumberValueType`/`isDynamicResourcesValueType`） | `value/logicboolean.json` + `param/logicboolean.json`；产出布尔函数/数值函数/单位计数/单位标记/`self`/内存变量/`选择` 多态/逻辑关键字/运算符，按上下文类型（BOOLEAN/NUMERIC/STRING/UNIT_MARKER/ANY）过滤；`translateValueName()` 统一剥括号翻译 |
| **EnumValueCompletionProvider**（枚举值） | 属性 type 命中枚举 | `raw/value/` 下对应枚举 json + `value/` 兜底 |
| **ImageValueCompletionProvider**（图片路径） | 属性名/type 命中 image hints（image/iconImage/...） | `ProjectImageCache` 扫描项目图片（ROOT:/ 相对路径） |
| **UnitSpawnCompletionProvider**（单位/抛射体生成） | 属性 type 为 spawnUnits/spawnProjectiles 类 | `raw/value/spawnUnits.json` / `spawnProjectiles.json`（产生单位/产生抛射体模板补全） |
| **AutoTriggerOnEventValueCompletionProvider**（事件触发） | 属性为 `autoTriggerOnEvent`（括号内抑制） | `raw/value/onActions.json` 等事件枚举，`translateValueName` 翻译 |
| **MemoryValueCompletionProvider**（内存变量） | 属性名含 memory/内存，或 `findProperty` name/name_en 含 memory；RHS 按 LHS 变量类型过滤（unit 型内存才进单位标记上下文） | 内存变量（`内存.变量名`），kvp 变量名位置插 `变量名=` |
| **TagValueCompletionProvider**（标签） | 属性 type 为 tag/tag list（`tags`/临时标签/消息标签/拦截抛射体等） | 项目标签 + 全局标签 + 消息标签集合 |
| **ResourceValueCompletionProvider**（资源） | type 关键字 price/resource/customprice/resources/dynamic resources 等；资源链 `<链>资源.` | 项目资源/全局资源名，链式补全（如 `自身资源.最新编号`） |
| **UnitNameValueCompletionProvider**（单位类型） | 属性 type 为 unitref/unitType/unitTypes（填单位类型名） | 项目单位名（`name:` 收集）+ 内存中 unit 型变量 |
| **UnitRefValueCompletionProvider**（单位标记） | 属性 type 为 unit ref/unit/marker/event（`isUnitMarkerType`）；统一 `unitMarkerItems`（self + logicboolean 中单位标记 + 标记链函数）+ unit 型内存变量 | 单位标记表达式（填 `self.父单位`/`创建标记(...)` 等） |
| **FunctionParameterCompletionProvider**（函数参数） | 光标在已知参数函数括号内（`isKnownParamFunctionContext`，参数表来自 `param/logicboolean.json` + `autoTriggerOnEvent`） | 参数名补全 + 参数值建议（`typeParamValues`、`relationValues` 等） |
| **ProjectRefValueCompletionProvider**（项目引用） | 属性 type 为 turret ref/projectile ref/effect ref/action ref 等（`projectRefKind`） | 对应命名节名（炮塔/抛射体/效果/行动/动画/贴花/附属/可建造） |
| **CopyFromSectionValueCompletionProvider**（复制节） | 属性为 `@copyFromSection`/复制节 | 继承链节名（按当前节基类过滤），中英文视图自适应 |
| **DefineVariableValueCompletionProvider**（变量定义） | 值前缀识别到 `${` | `@define`/`@global` 变量名 |
| **CrossSectionRefValueCompletionProvider**（跨节引用） | 值前缀匹配 `${节名.`（括号内含未闭合 `${` 也触发） | 该节属性名/值补全，节名归一化（section→当前节、英文→中文） |

#### 数据加载与缓存

- **ValueDataLoader**（object）：`load(context, name): ValueCategory`，加载 `assets/data/value/<name>.json` 或 `raw/value/<name>.json`。
- **ParamDataLoader**（object）：加载 `assets/data/param/logicboolean.json`，`ParamItem(key, zh, type, values, expression)`（`expression=true` 表示数值参数接受逻辑表达式）。
- **ProjectImageCache**（object）：后台扫描项目图片并缓存 ROOT:/ 相对路径（png/jpg/jpeg/gif/bmp）。
- **ProjectSoundCache**（object）：后台扫描音频文件（ogg/wav/mp3），供声音引用补全。
- **LogicExpressionContext.kt**：逻辑表达式上下文分析工具：
  - `LogicTarget` 枚举：`BOOLEAN/NUMERIC/STRING/UNIT_MARKER/ANY/UNKNOWN`。
  - `classifyCallableCategory(name, type)`：可调用对象分类（单位标记/数值表达式/布尔值/布尔表达式/文本表达式/连接符/运算符/任意类型）。
  - `CALLABLE_CATEGORIES`：+号菜单/补全查看器统一分类列表。
  - `isUnitMarkerType(type)`：单位标记类型统一判定（覆盖 unit/marker/event，排除 unitref/unittype/unitname）。
  - `logicTargetOfBaseType(type)`：基础类型→逻辑目标类型。
  - `classifyLogicType(...)`：逻辑表达式类型分类。

---

## 10. 坐标可视化模块 feature/coord/

**职责**：解析、求值、渲染坐标表达式（`创建标记(...)` 等），支持拖拽反向写回代码。

- **CoordAst.kt**：`sealed class AstNode`（`NumberLiteral`/`Identifier`/`PropertyAccess`/`NamedArgument`/`Call`/`BinaryOp`/`UnaryOp`）。
- **CoordParser.kt**：`parseCoordExpression(text): AstNode?` 递归下降解析。
- **CoordEvaluator.kt**：`evaluateCoordExpression(node, ctx)` 求值；`EvalContext`/`CoordUnit`（self/标记/资源/目标等上下文对象，支持属性访问与内置函数 distance/direction 等）。
- **CoordScene.kt**：场景数据：`CoordSelf`/`CoordTarget`/`CoordResource`/`CoordMarker`；`parseCoordMarkers(text)`（提取 `创建标记` 调用）、`discoverTargetNames`、`buildDefaultTargets`、`recalcMarkers`、`reverseOffset`（拖拽反向计算偏移）。
- **CoordCanvas.kt**：`@Composable fun CoordCanvas(...)` 绘制网格/坐标轴/标记/箭头/菱形，`DragTarget` 命中测试。
- **CoordControlPanel.kt**：`@Composable fun CoordControlPanel(...)` 控制面板（self 坐标、目标坐标、时间烘焙）。
- **CoordCodeUpdater.kt**：`applyMarkerOffsetToText`（把标记偏移写回文本）、`formatCoordNumber`、`bakeTimeIntoCode`。

---

## 11. UI 层 ui/

### 11.1 MainApp.kt — 导航壳

**职责**：全局导航（`ModalNavigationDrawer` + `Scaffold` + 全局 topBar）、返回栈管理、跨页面状态。

**页面枚举**：`enum class Screen { HOME, BROWSER, EDITOR, CODE_REF, SETTINGS, TRANSLATION, CUSTOM, DEVELOPER, COMPLETION_VIEWER, COORD_VISUAL, VERSION_COMPARE, PROJECT_MANAGER, TODO_LIST }`。

**导航核心**：
- `pushScreen(target)`：当前页压入 `backStack`（上限 20），切到目标页。
- `popScreen(default)`：弹栈顶返回。
- `clearBackStack()`：抽屉切到顶层页时清栈。
- **返回逻辑：从哪个页面打开，返回哪个页面**（BackHandler + 顶部返回按钮按 Screen 分发）。
- 关键状态用 `rememberSaveable`（`currentScreen`、`selectedFile`、`saveAndExit` 等），配自定义 `Saver`（Screen/pair/nullable）。

**全局 topBar**：编辑器页显示文件名 + 最近/背景色/高亮主题/启动游戏按钮；项目管理页显示待办入口；待办页显示添加按钮；补全查看页显示可调用对象分类「+」菜单。

**跨页面协作**：
- `cacheEditorStateBeforeLeave()`：抽屉离开编辑器时缓存文本并可触发保存。
- 查重流程：`checkProjectDedup`（未注册项目先查重，通过后 `ProjectRegistry.registerProject` 再进文件浏览）与 `doDedupCheckOnly`。
- `scanProjectSymbols`：项目打开时后台 `ProjectTagScanner.scanIfNeeded` 扫描。
- 全局底部弹窗：首页搜索（`showHomeSearchSheet`）、近期修改（`showRecentSheet`），记录来源页面以返回。
- 外部文件打开：消费 `MainActivity.pendingOpenFile`，有未保存修改时先确认保存。

### 11.2 EditorScreen.kt — 编辑器页面

**职责**：文件编辑核心页。参数很多（fileName/filePath/autoWrap/smartWrap/外部插入与替换/jumpLine 跳转/saveTrigger 保存触发等）。

**核心状态**（均按 `filePath` 记忆，切换文件立即重置，避免串写）：
- `text` / `latestEditorText` / `englishText` / `chineseText` / `showChinese` / `isEnglish` / `isModified` / `currentSection`。
- `fileSymbols` / `chainSymbols`（补全用文件级符号缓存，后台提取）。
- `editorRef`（sora `CodeEditor` 引用）。

**关键函数**：
- `saveSync(caller)`（suspend，`Dispatchers.IO`）：
  1. `contentLoaded` 未加载则跳过。
  2. 快照 `targetPath = filePath`。
  3. 优先读 `latestEditorText`（编辑器实际内容，避免跨线程 runBlocking 读旧内容）。
  4. 仅 `isEnglish && showChinese` 时先 `engine.translateToEnglish(currentText, autoSpace, litLines)` 反译。
  5. 写盘 → 校验 `targetPath == filePath`（路径变化打警告）→ toast/onSaved。
  6. `SaveHistoryManager.record(ctx, targetPath, beforeContent, contentToSave)`。
  - 所有保存调用带 `caller` 标记（BackHandler/SaveAndExit/LifecycleOnPause/TabSwitch/MenuSave/BackConfirm）。
- `parseSectionsImmediate(currentText, cursor, caller)`：立即解析节名（取光标行之前最后一个 `[节]`），填充 `allSections` 与 `currentSection`。
- `copyToClip` / `safeEditorOp` / `safeSearcherOp`：操作封装。

**加载与联动**：
- `LaunchedEffect(Unit)`：确保翻译引擎加载 → 加载三表（用户/原生/附件补全）→ 后台 `ProjectImageCache`/`ProjectSoundCache` refresh → 构造 `CompletionProvider`。
- `LaunchedEffect(filePath)`：`snapshotFlow { text to cursorPos }.debounce(250)` 后节名解析（**必须用 filePath 作 key**，否则切换文件后仍观察旧状态）。
- 行尾灯泡：`blockableStringKeys`（`engine.getBlockableStringKeys`），`litLines`/`lineOriginalTexts` 记录用户点亮翻译的行，保存时反译这些行。
- 查找替换：由 sora 原生 `EditorSearcher` 处理，搜索/替换文本 500ms 防抖持久化。
- 外部插入（调色盘颜色）：`externalInsertTick` 触发 `insertTextRequest`。
- 外部整文本替换（坐标可视化反写）：`externalReplaceTick` 触发。
- 搜索/近期跳转：`jumpTick` 触发 `setSelection`/`setSelectionRegion`，中文视图下用 `mapEnglishColumnsToChineseImpl` 映射列位置。

### 11.3 其他页面（screens/）

| 页面 | 职责 |
|---|---|
| **HomeScreen** | 首页：模组列表、打开文件夹/项目、近期修改、搜索、`.rwmod` 打包/解压、版本对比入口 |
| **FileBrowserScreen** | 文件浏览：目录树、搜索（文件名/内容）、近期修改、标签过滤 |
| **CodeReferenceScreen** | 代码表：按节浏览/搜索属性文档（`CodeReferenceRepository`） |
| **SettingsScreen** | 设置：主题、编辑器、字体、补全、节过滤、翻译、数据管理（导入/导出配置） |
| **TranslationEditorScreen** | 翻译库：三表（原生/附件/用户）浏览、编辑、查重、屏蔽词管理 |
| **CustomCompletionsScreen** | 自定义补全：自定义表（CustomCompletion）增删改、格式（属性/values/特定值）、分类 |
| **DeveloperModeScreen** | 开发者模式：补全/解析/翻译/UI/保存子开关 |
| **CompletionViewerScreen** | 补全查看器（开发者工具）：DemoPanel 演示、属性列表、可调用对象分类浏览（`CALLABLE_CATEGORIES`） |
| **CoordVisualScreen** | 坐标可视化（§10） |
| **VersionCompareScreen** | 版本对比（`VersionComparator`） |
| **ProjectManagerScreen** | 项目管理：标签/全局标签/资源/内存/单位名分类浏览、待办入口 |
| **TodoListScreen** | 待办列表（`TodoManager`） |
| **OnboardingScreen** | 首次引导：权限/默认目录设置（完成写验证码 `ONBOARDING`） |

### 11.4 ui/components/

- `CommonUiComponents.kt`：通用组件（加载/空状态/标签 chips 等）。
- `ColorPickerDialog.kt` / `ColorWheelPicker.kt`：颜色选择。
- `CustomCompletionEditorDialog.kt`：自定义补全编辑。
- `DarkTokenColorDialog.kt`：深色高亮 token 颜色编辑。
- `DedupResultDialog.kt`：查重结果。
- `RainbowBracketSettingsPanel.kt`：彩虹括号参数面板。

### 11.5 ui/theme/

- `Color.kt`（Rusted 品牌色：RustedPrimary/RustedBackground 等）、`Fonts.kt`（字体族）、`Theme.kt`（`RustedWarfareModStudioTheme`）。

---

## 12. 工具类 util/

| 文件 | 职责与关键函数 |
|---|---|
| **FileImportHelper.kt** | `importFromUri(context, uri, dir)`（把 content:// 复制到目标目录）、`uriToAbsolutePath(uri)` |
| **IniImageReader.kt** | 读取 INI 中图片字段并解码（⚠️ 已知性能风险：主线程同步 readText + decodeFile，无 inSampleSize） |
| **UriUtils.kt** | URI 工具（scheme 判断、display name 获取等） |

---

## 13. 数据与资源文件

### 13.1 assets/data/

| 文件 | 用途 | 加载方 |
|---|---|---|
| `code_reference.json` | 代码参考库主表（唯一数据源） | `CodeReferenceRepository`（运行时副本 `RWmod/cache/code_reference.json`） |
| `translation.txt` | 原生翻译库 | `TranslationDict`（`RWmod/translation/native_translation.txt` 副本） |
| `extra_completions.json` + `extra_translation_supplement.txt` | 附件补全表与补充翻译 | `TranslationDict`（`extra_translation.txt` 副本） |
| `snippets.json` | 代码片段 | `SnippetRepository` |
| `param/logicboolean.json` | 函数参数表（参数名/类型/可选值/是否接受表达式） | `ParamDataLoader` |
| `raw/sections/*.json` | 各节定义（节补全/过滤） | 补全 |
| `raw/value/*.json` | 枚举/布尔等值数据 | 各值 Provider |
| `value/*.json` | 值补全数据（逻辑/产生单位等） | `ValueDataLoader` |

### 13.2 运行时生成文件（均在 `RWmod/` 下，详见 §6.1）

- `translation/`：user/native/extra 翻译表 + 合并缓存（txt + meta + sources）。
- `completions/`：native/extra/user/custom 补全表（含 `_en` 英文版）。
- `config/`：settings.json、verify.json、translation_blocklist.json、section_filters.json、verified_behaviors.json、project_registry.txt、save_history.json。
- `cache/`：code_reference.json、file_translation/、inheritance/。
- `dedup/`、`todos/`、`imports/ini`、`exports/`、`migrated/`。

---

## 14. 模块依赖与数据流

### 14.1 数据流向图（翻译/补全）

```
assets/data/{translation.txt, extra_*, code_reference.json, snippets.json}
        │  验证码(VerifyManager) 控制首次/强制拷贝
        ▼
RWmod/ 下的运行时副本（native_translation.txt / translation_cache.txt / code_reference.json）
        │
        ▼
TranslationDict ──► TranslationEngine（翻译 API：translateToChinese/English）
        │                    │
        │                    ├──► CompletionProvider.getCompletions(...)
        │                    │        │
        │                    │        ├──► ValueCompletionAggregator ──► 16 个 Value Provider
        │                    │        │              │（依赖 ValueCompletionRequest.findProperty/toEnglishName）
        │                    │        │              └──► CodeReferenceRepository（.type 判定）
        │                    │        │              └──► ProjectTagScanner / ProjectImageCache / ProjectSoundCache
        │                    │        └──► 翻译库兜底（allDictKeys）
        │                    │
        │                    └──► EditorScreen（行尾灯泡/整文件翻译）
        ▼
CodeReferenceRepository（getRealSectionNames / searchProperties）
```

### 14.2 启动初始化依赖链

```
MainActivity.onCreate
  └─ SettingsManager.init
  │     ├─ RwmodPaths.rwmodDir
  │     ├─ VerifyManager.init（迁移旧验证码）
  │     ├─ FileSettings.create（迁移 SharedPreferences）
  │     ├─ migrateFileStorage（旧路径迁移）
  │     └─ migrateDefaults / initLocalFiles / pruneRecentFiles
  ├─ TranslationEngine.loadBlocklist
  ├─ ArrayReader.init（jcodings）
  └─ applicationScope.launch(IO)   // 串行后台初始化
        ├─ loadExtraItemsVerified（附件补全表）
        ├─ TranslationEngine.load（翻译引擎 + 代码参考库 + 片段）
        ├─ loadNativeItemsVerified（原生补全表）
        ├─ refreshCompletionsFromEnglish（补全翻译刷新）
        ├─ SearchTranslationCache.prepareIfNeeded（翻译缓存预热）
        └─ InheritanceCache.prepareIfNeeded（继承链预热）
```

### 14.3 编辑器保存链路

```
EditorScreen.saveSync(caller)
  ├─ 快照 targetPath
  ├─ 读 latestEditorText
  ├─ isEnglish && showChinese → engine.translateToEnglish(text, autoSpace, litLines)
  ├─ 写盘 + 校验 targetPath == filePath
  ├─ SaveHistoryManager.record（RWmod/config/save_history.json）
  └─ onSaved / 抽屉离开 cacheEditorStateBeforeLeave（autoSave → editorSaveTrigger++）
```

### 14.4 值补全触发依赖

```
EditorScreen（当前节名/符号缓存）─► CompletionProvider.getCompletions
  └─ valueAggregator.getValueCompletions(request)
        └─ 每个 Provider.canProvide(request)
              └─ request.findProperty()  // 依赖 CodeReferenceRepository.sectionProperties
              └─ 数据源（ValueDataLoader / ParamDataLoader / ProjectTagScanner / 缓存）
```

---

## 15. 运行与构建

### 15.1 前置条件

- JDK 17（仓库自带 `tools/jdk/jdk-17.0.19+10`）。
- `local.properties`：配置 `sdk.dir` 与 release 签名（`RELEASE_STORE_FILE` 等）。

### 15.2 常用命令（PowerShell）

```powershell
# 设置 JDK 环境（本机）
$env:JAVA_HOME = "D:\ALSO2004\android-tool\RustedWarfareModStudio\tools\jdk\jdk-17.0.19+10"

# Debug 构建（约 21MB，未启用 R8）
.\gradlew.bat assembleDebug

# Release 构建（R8 + 资源压缩，约 4.3MB，对外发布用）
.\gradlew.bat assembleRelease

# 安装到已连接设备
.\gradlew.bat installDebug

# 运行单元测试
.\gradlew.bat test
```

### 15.3 发布版本号规则

版本号在 `app/build.gradle.kts`：`versionCode`（每次发布必须 +1，只增不减）+ `versionName`。

---

## 16. 单元测试

测试位于 `app/src/test/java/com/rwmodstudio/`（JVM 单测，`isReturnDefaultValues = true`）。运行：`.\gradlew.bat test`。

覆盖范围（节选）：

| 测试类 | 覆盖内容 |
|---|---|
| `core/translation/TranslationEngineTest.kt` | 翻译引擎（中英互译/多行字符串/屏蔽词） |
| `core/translation/CodeReferenceDefaultTest.kt` | 代码参考库 default 补全值 |
| `core/translation/TranslationValueParenMatchTest.kt` | 值翻译括号匹配 |
| `core/DiffUtilTest.kt` / `core/VersionComparatorTest.kt` | 行级 diff / 版本对比 |
| `core/ProjectTagScannerScanTest.kt` / `core/InheritanceChainSymbolsTest.kt` | 项目符号扫描 / 继承链符号 |
| `feature/completion/CompletionParsingTest.kt` / `CompletionSymbolsTest.kt` / `ValueDedupTest.kt` | 补全解析/符号/去重 |
| `feature/completion/value/ValueProviderTypeTest.kt` / `ValueCompletionContextTest.kt` / `FunctionParameterCompletionTest.kt` / `CopyFromSectionCompletionTest.kt` / `ProjectRefKindTest.kt` / `UnitNameListPropertyTest.kt` | 各值 Provider 触发/上下文 |
| `ui/screens/EnToZhLookupPriorityTest.kt` / `CustomCompletionsFormatTest.kt` | 翻译查询优先级 / 自定义补全格式 |
| `editor/SmartWrapBreaksTest.kt` | 智能换行 |

---

## 17. 关键约定与常见陷阱

### 17.1 项目硬性要求（docs/项目要求.txt）

1. 返回逻辑：从哪个页面打开，返回哪个页面。
2. 页面已有全局标题栏，不额外新增。
3. 生成文件不出 `RWmod` 目录。
4. 生成文件配验证码。
5. 代码参考表仅参与本地自定义补全表原生表的生成。

### 17.2 关键约定（改动前必读）

- **路径**：一律通过 `RwmodPaths` 获取，不硬编码。
- **验证码**：新增「强制更新」能力的生成文件必须走 `VerifyManager`。
- **翻译**：所有中文 label 经翻译引擎，不硬编码中文；`self.xxx()` 查询顺序先含 self 后裸名。
- **节名映射**：统一用 `CompletionProvider.sectionEnToZh`。
- **单位标记 vs 单位类型**：`unit ref`（带空格）填表达式；`unitref`（无空格）填单位类型名；`isUnitMarkerType()` 统一判定。
- **UI 分类**：展示用 `getRealSectionNames()`，不用 `getAllSectionNames()`。
- **`LaunchedEffect`**：编辑器相关一律用 `filePath` 作 key。
- **并发写 JSON**：参考 `TranslationEngine`/`FileSettings` 的 Mutex/单线程写法。
- **编译**：勿删 `assets/tables/`（jcodings 依赖）；勿改 `applicationId`（等于发布新应用）。

### 17.3 常见陷阱

1. UI 分类混入 value 类别名 → 误用 `getAllSectionNames()`。
2. 切换文件后节名不更新 → `LaunchedEffect` 没用 `filePath` 作 key。
3. 删除 `assets/tables/` → sora-editor/TextMate 崩溃。
4. 保存丢内容 → 未等待异步保存/未快照路径/英文视图存旧文本。
5. 原生表未刷新导致翻译变更不生效 → 递增验证码强制重生成。
6. `assets/tables/` 不受 `packaging.resources.excludes` 控制（是 assets 不是 Java resources）。

---

*本文档由源码分析自动生成，更新于仓库构建后的某个时间点。若与代码不一致，以代码为准。*
