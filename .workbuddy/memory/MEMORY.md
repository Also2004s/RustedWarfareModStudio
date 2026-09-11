# RustedWarfareModStudio 项目长期笔记

## 报告格式约定（老板要求，2026-09-01）

- **docs/ 下所有报告用「用户视角三层式」**：①为什么（大白话成因+触发条件）②改之前（用户现象）③改之后（用户变化）；每条标严重度；按功能名组织；技术细节放末尾单独一节。样板：`docs/bug-audit-round2-2026-09-01.md` 效果版章节。

## 关键约定 / 易误解点（重要）

- **用户报的问题一定真实存在（2026-09-01 血泪教训）**：凡是用户反馈 bug，**先信用户、先自查自己**——不要用"你装的是旧版"这类借口搪塞。排查顺序：① 查设备上实际装的是哪个包（正式 `com.rwmodstudio` vs 测试 `com.rwmodstudio.debug`，**测试版是 debug 包**，用 `adb shell dumpsys package com.rwmodstudio.debug` 看 lastUpdateTime）；② 验证 APK 构建时间 ≥ 源码修改时间；③ 源码里 grep 修复是否真的在。上次就是因为只查正式包时间戳、断定"设备是旧版"，被用户纠正——**凡事先思考自己的问题**。
- **翻译引擎反向翻译的「归一化」是设计行为，不是 bug**：`self.` 前缀丢失、空括号 `()` 丢失、注释前空格规整，均为有意设计——反向翻译统一返回「翻译库样式（裸英文）」，补全 label 与保存还原共用同一预期。**不要再当成 bug 去"修复"**。翻译引擎目前没有问题。
- **保存流程优化方向（用户想法，未实施）**：保存时只对 diff 内容做翻译替换，而非整篇反向翻译覆盖。属未来优化，非当前任务。
- **「翻译功能没了/缓存不加载/进度弹窗不显示」第一检查点 = `devTranslationEngine` 开关**（2026-09-01 实测）：该开关关闭后翻译引擎不加载、`SearchTranslationCache.prepareIfNeeded` 不预热、相关 TaskProgressManager 弹窗全不显示——**设计行为，不是 bug**。它是 FileSettings 代理属性（`grep -n "devTranslationEngine"` 单文件可能匹配不到），用全仓 grep。另有 `devDebugTaskProgress`（进度浮窗总开关，默认 true）。
- **`loadBlocklist` 只能在主线程同步调**（TranslationEngine.load 的 loadMutex 内也会调它，屏蔽词加载依赖翻译字典先就绪）：**不要**把它挪到后台协程，会与 load 并发竞态。MainActivity onCreate 四行顺序（SettingsManager.init → loadBlocklist → ArrayReader.init → cleanupStale）是 HEAD 验证过的，别动。
- **原子写只用于「用户数据」，不用于「可再生的缓存/输出」**（2026-09-01 多线程失效教训）：编辑的文件/设置/历史/待办用 atomicWriteText；翻译缓存/继承缓存/查重输出/补全表用直接 writeText——它们损坏了重新生成即可，原子写（tmp+rename+锁）在高频写盘时把多线程加速完全掩盖。**判断"要不要原子写"的标准是"数据是否可再生"**。
- **热路径禁锁**：`TaskProgressManager` 保持无锁（每文件 update 都调，加锁全项目进度更新串行）；`isFresh` 里的 libVersion 判定直接读 @Volatile 字段（会话内恒定），不要加 TTL+synchronized（每文件调用串行化预热）。
- **GitHub 是超级旧版**：`git diff` 对比 GitHub 无意义（含大量非本对话改动）；判断"谁改的/改了什么"只能靠会话内改动清单（38 点）+ 代码调用链，不能靠 git diff。
- **mod-info.txt 是「特殊文件」（2026-09-01 老板纠正）**：它的补全（[mod] 的 title/description/tags/minVersion/thumbnail + [music] 的 sourceFolder/whenUsingUnitsFromThisMod_playExclusively）应该**按文件名判断启用**（仅打开的文件名是 mod-info.txt 时），**不能**把 mod/music 注册成全局节（sectionEnToZh/DEFAULT_SECTION_FILTERS）——会污染普通 ini 的补全。**改验证码（SECTION_FILTERS_CODE 等）= 强制重置所有用户的该配置**，属用户敏感操作，必须先问老板。首页缩略图已支持 mod-info.txt 的 thumbnail: 键优先（HomeScreen.resolveThumbnailFile，老板确认保留）；补全重做「先不做」，等老板发话。

## 架构现状（编辑器多实例）

- 最多 5 个 EditorScreen 实例并存（`openPaths` MRU），前台 1 个渲染高亮，后台 4 个 `active=false` 停高亮但状态保留。
- 实例销毁落盘统一走 `DisposableEffect.onDispose` → `onDisposeFlush`，无 delay 依赖。
- 任意保存行为触发 `flushInactiveInstances()` 收尾（其它实例写盘并移除，只留前台）。
- MOD 文件写盘统一用 `AtomicWrite.atomicWrite`（.tmp + rename），**per-path 锁**（2026-09-01 从全局锁改为 per-path：全局锁会把查重/预热等并行写不同文件的场景串行化导致性能退化；同路径才需串行防 tmp 冲突）。

## sora-editor 主题陷阱（改主题/多实例必读）

- **全局派发会重置所有实例颜色**：`applyEditorTheme` → `getColorScheme` → `ThemeRegistry.setTheme` 会**无条件 dispatchThemeChange** 给所有存活 `TextMateColorScheme`，触发 `setTheme` → `colors.clear()` + `applyDefault()`，把各实例自定义颜色（含背景 `WHOLE_BACKGROUND`）清空回退默认主题色。所以「重建方案（`editor.colorScheme = 新 scheme`）」会污染其它实例；「仅对现有方案 `setColor`」不会。
- **约定**：写自定义颜色统一走 `SoraCodeEditor.kt` 的 `applyEditorColors(...)`（对现有 scheme 重放颜色）；实例重新激活（切回）时在 `LaunchedEffect(active, editorRef.value)` 里对它调 `applyEditorColors` 恢复背景/高亮，不要再重建方案。
- **主题文件解析**：tm4e-core（随 `language-textmate:0.24.6` 内嵌）提供 `IThemeSource.fromInputStream` + `RawThemeReader.readTheme` 自动分派 JSON 与 plist(.tmTheme)，无需自写解析器。配色映射可走 `org.eclipse.tm4e.core.theme.Theme.match(ScopeStack.from(scope))` + `getColor(attrs.foregroundId)`。
- **事件拦截**：`io.github.rosemoe.sora.event.EditorKeyEvent` 拦截方法是 `markAsConsumed()`（**不是** `handle()`，实测 javap 验证 0.24.6）。

## material3 1.2.1 API 陷阱（改 M3 组件必读）

- `RectangleShape` 在 **`androidx.compose.ui.graphics`** 包（不在 `foundation.shape`）。
- `ModalDrawerSheet` **没有 `drawerElevation`**（1.3 才有），1.2.1 用 `drawerTonalElevation`。
- `NavigationDrawerItemDefaults.colors()` 只有 8 个参数（selected/unselected × container/icon/label/badge），**没有 `disabledContainerColor`**。

## 环境

- JDK: `tools/jdk/jdk-17.0.19+10`；构建：`export JAVA_HOME=<项目>/tools/jdk/jdk-17.0.19+10 && ./gradlew.bat installDebug`
- 设备 V2505A（Android 16，vivo，安装需先开 USB 安装权限）。
- **Agent 内构建的守护进程坑**：多次出现 "Gradle build daemon has been stopped" / "Could not delete kotlin-classes"，根因是残留 GradleDaemon/KotlinCompileDaemon 抢锁。可靠配方：`jps -l` 找到 GradleDaemon/KotlinCompileDaemon PID → `taskkill /F /PID ...` 全清 → 删 `~/.gradle/daemon/8.14.5/registry.bin` → 前台 + `--no-daemon` 跑 `assembleDebug`（别与 `--stop` 混用、别后台跑）。APK 用 `adb install -r` 直装（`C:\Users\Also2\AppData\Local\Android\Sdk\platform-tools\adb.exe`）。
- **Kotlin 增量编译对「修改后文件被新增文件引用」可能报 unresolved**（如新增的 HighlightThemeManager 引用 ThemeState.applyPresets，但编译报 unresolved；ThemeState.kt 实际存在该方法）。解法：`./gradlew <task> --rerun-tasks` 强制全量重编。同样的症状：`./gradlew ... | tail -20` 截断管道会让 head/tail 提前退出导致 Gradle 变孤儿（记忆里「管道截断」坑）——**输出重定向到文件** `> build.log 2>&1` 再 tail，比管道安全。
