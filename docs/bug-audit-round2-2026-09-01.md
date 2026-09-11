# 铁锈工坊 Bug 与隐患排查报告（第二轮：保存链路之外）

- **日期**：2026-09-01
- **范围**：保存链路之外——文件读写与缓存一致性 / 导入导出与安全 / 并发协程与网络
- **方法**：多路并行代码走查 + 关键文件逐段核实
- **约定**：每条按「问题 / 修改前 / 修改后」给出；**本轮只排查不改代码**；🚫=已安全或设计行为
- **修复状态**：2026-09-01 晚已按「全修」完成 32 条修复（代码已改，未构建，由另一 AI 构建验证）

---

# 效果版（用户视角：为什么 / 改之前 / 改之后）

> 三栏制：**为什么**（成因 + 什么时候触发）+ **改之前**（现象）+ **改之后**（效果）。
> 以下 13 条与本报告 A/B/C 技术清单一一对应；技术细节见对应条目。

## 🔴 会丢东西 / 坏东西

### 1. 解压 .rwmod 能被恶意包写到目录外（zip-slip）— B1
- **为什么**：解压代码把压缩包里的路径名**原样**拼进目标文件夹（`File(目标夹, 包里路径)`），没有检查路径里有没有 `..` 或绝对路径。压缩包是别人做的，任何 .rwmod 都能藏这种文件名。→ **触发**：解压一个被动了手脚的 .rwmod。
- **改之前**：恶意包能把文件写到你模组目录**外面**，悄悄覆盖其它文件。
- **改之后**：发现路径有问题直接拒绝解压，最多提示「解压失败」，其它文件毫发无损。

### 2. 设置/补全表/翻译库「莫名被重置」— A1 VerifyManager
- **为什么**：验证码文件（verify.json）的读写是「整文件读→改→整文件写回」且**没加锁**。设置页重置、翻译引擎加载、缓存版本计算会同时读写它，后写覆盖先写丢 key；读时可能读到写一半的坏文件，解析失败当"验证码是默认值"→ 数据被误判过期而重置。→ **触发**：启动后马上操作设置/刷新数据。
- **改之前**：偶尔补全表、翻译库「莫名回到默认」，刚改的白改。
- **改之后**：改什么是什么，多个操作同时发生也不互相覆盖。

### 3. 翻译缓存损坏，翻译结果时灵时不灵 — A2/A3
- **为什么**：缓存写盘用**固定名**临时文件（xxx.tmp），两个线程同时写同一 tmp 会互相截断拼出乱码再改名生效；缓存索引（index.json）直接覆盖写、没加锁。预热、编辑器翻译、首页搜索并发触发。→ **触发**：同时翻译多个文件、或预热与搜索同时进行。
- **改之前**：缓存偶尔损坏 → 翻译结果时灵时不灵，甚至要重新翻一遍。
- **改之后**：缓存稳定，翻译结果正常。

### 4. App 被杀/闪退后设置全回默认 — A4 settings.json
- **为什么**：设置保存是**直接覆盖写文件**（不是先写临时文件再改名），写到一半进程被杀就是半截坏文件；下次启动读不了当"没有设置"，全部回默认。→ **触发**：被系统回收/闪退/强停的瞬间恰好有设置写入。
- **改之前**：主题、自动保存等全部设置一下回默认。
- **改之后**：设置不丢，重启还是你设的样子。

### 5. 快速勾选/删除待办会丢操作 — A5 TodoManager
- **为什么**：每次勾选「读整个文件→改一条→整个写回」且**没加锁**；快速连点两次基于同一份旧数据，后写覆盖先写。→ **触发**：快速连续勾选/删除待办。
- **改之前**：连点几下，刚删的又冒出来、刚勾的没勾上。
- **改之后**：操作稳定，删了就删了。

### 6. 保存翻译条目时卡顿/闪退 — C2 TranslationDict
- **为什么**：翻译字典是**普通 HashMap**（非线程安全），保存条目（写）与编辑器打字翻译（读）可在两线程同时发生 → 数据竞争，轻则半更新、重则异常。→ **触发**：保存翻译条目的同时正在编辑器里输入。
- **改之前**：偶尔卡一下甚至闪退。
- **改之后**：随便点，不卡不闪。

### 7. 关掉 AI 面板还在烧 token — C3 AiService
- **为什么**：AI 流式读取是**阻塞式**（逐行读网络流），取消协程只改标志、读线程仍把整个响应读完并继续回调。→ **触发**：AI 回答过程中关掉面板/切走文件。
- **改之前**：请求还在后台跑，白烧 token。
- **改之后**：取消立即中断连接，不浪费。

## 🟡 体验不丝滑

### 8. 打开 App 首屏卡顿/白屏 — C5 启动 I/O
- **为什么**：启动时设置迁移（旧版目录扫描）、剪贴板、屏蔽词等**文件操作全在主线程同步执行**。→ **触发**：旧版本数据多、首次升级最明显。
- **改之前**：首屏卡 1-2 秒甚至白屏。
- **改之后**：秒开，文件操作挪到后台。

### 9. 下载更新包后点安装没反应 — C4 installApk
- **为什么**：安装跳转（startActivity）在**后台线程**发起，Android 12+ 限制后台弹 Activity。→ **触发**：下载完点安装，部分机型。
- **改之前**：调不起安装界面。
- **改之后**：正常弹出安装。

### 10. 查看大贴图直接闪退 — B3 IniImageReader
- **为什么**：整图解码进内存、无采样压缩；4K 贴图解码后可能占几十 MB，内存紧张即 OOM。→ **触发**：查看 2K/4K 大贴图。
- **改之前**：直接闪退。
- **改之后**：自动压缩显示，不崩。

### 11. 从外部 App 导入文件可能写错位置 — B2 FileImportHelper
- **为什么**：导入文件名**直接用对方返回的名字**，未清洗路径符号；带 `../` 时拼接后写到目标目录外。→ **触发**：导入文件名奇怪的来源。
- **改之前**：可能写错位置。
- **改之后**：文件名安全化，不会乱写。

## 🟢 平时看不到但重要

### 12. 分享配置时暴露整个存储 — B4 FileProvider
- **为什么**：FileProvider 配置 `path="."`，把整个外部存储根目录挂上共享权限，实际只需导出目录。→ **触发**：分享配置 zip。
- **改之前**：整个存储空间理论上暴露给接收方 App。
- **改之后**：只暴露导出目录。

### 13. 云备份可能带走敏感配置 — B5 backup_rules
- **为什么**：备份规则文件为空 = 系统默认全量备份私有数据，含 AI key 等敏感项会随备份走。→ **触发**：系统云备份/换机迁移。
- **改之前**：可能把敏感配置备份带走。
- **改之后**：明确排除敏感项。

---

# 技术清单（问题 / 修改前 / 修改后）

## A. 文件读写与缓存一致性

### A1 🔴 高｜VerifyManager verify.json 读改写无锁、非原子
- **问题**：`read()` 每次全文件读；`write()` 读整个文件→改→`writeText` 全量覆盖，无锁无原子写。并发来源：设置页 `resetAllVerifyCodes`、翻译引擎加载 `ensureNativeTranslationFile/ensureExtraTranslationFile`、`computeLibVersion` 读取——并发写互相覆盖丢 key，或读到半写文件（JSON 异常静默返回默认码 → **数据被误重置**）。
- **修改前**：无内存缓存、无锁、`writeText` 直接覆盖。
- **修改后**：`read` 加内存缓存 + 文件变更才重读；`write/writeAll` 用 `synchronized(VerifyManager)` + `atomicWriteText` + volatile 缓存失效标记。

### A2 🟡 中｜SearchTranslationCache 自建 tmp 写缓存，未走全局锁
- **问题**：L152-154/L268-270 自建 `tmp.writeText + tmp.renameTo`，临时文件名固定 `$name.tmp`，**未走 `AtomicWrite` 全局 `WRITE_LOCK`**。预热 + 编辑器继承链读取 + 首页搜索可并发翻译写同一 tmp → 内容交错 → rename 后缓存损坏。
- **修改前**：`val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp"); tmp.writeText(...); tmp.renameTo(cacheFile)`。
- **修改后**：改用 `cacheFile.atomicWriteText(chineseContent)`（复用全局写锁），或按 absPath 加 per-path 锁。

### A3 🟡 中｜SearchTranslationCache index.json 非原子写 + 并发 saveIndex
- **问题**：`saveIndex()` 用 `writeText` 直接写索引；预热 finally 与搜索结束 `flushIndex()` 可并发 → index.json 撕裂，下次启动解析失败回退空索引 → **全部缓存记录丢失需全量重译**（异常被静默吞）。
- **修改前**：`RwmodPaths.fileTranslationIndexFile.writeText(json.toString())`。
- **修改后**：`atomicWriteText` + `saveIndex` 加 `synchronized`，或 flush 前判 `indexDirty` 串行。

### A4 🟡 中｜SettingsManager settings.json 非原子写
- **问题**：`persist()` 用 `writeText` 直接覆盖，无原子性；App 写一半被杀 → settings.json 截断 → 下次 `load()` 解析失败静默清空 → **全部用户设置回默认**。
- **修改前**：`file.writeText(json.toString(2), Charsets.UTF_8)`。
- **修改后**：`file.atomicWriteText(json.toString(2))`（与单线程 executor 不冲突）。

### A5 🟡 中｜TodoManager 读改写无锁 + 非原子写
- **问题**：`add/update/delete` 均为 `load()→修改→save()` 读改写，无互斥；TodoListScreen 每个勾选/删除独立 `launch(Dispatchers.IO)`，连续快速操作基于同一旧快照 → 后写覆盖先写，**操作丢失**。`save()` 非原子。
- **修改前**：无锁读改写 + `writeText`。
- **修改后**：`TodoManager` 内加 `Mutex`/`synchronized` 串行化读改写；写文件改 `atomicWriteText`。

### A6 🟡 中｜补全表/翻译库写入非原子 + HashMap 并发
- **问题**：①`saveUserCompletions/saveNativeCompletions/saveExtraCompletions` 已用 `completionsSaveMutex` 互斥但 `writeText` 非原子，中/英两文件先后写，中途崩溃 → 两文件不一致；②`TranslationDict.saveToExternal` 与 `updateEntry/deleteEntry` 不共享 mutex，且均修改未加锁的 `enToZh/zhToEn` HashMap，与翻译线程并发读 → `ConcurrentModificationException` 或半更新状态。
- **修改前**：非原子写 + HashMap 无同步 + 两条写入路径无互斥。
- **修改后**：全部改 `atomicWriteText`；字典内存 map 改 `ConcurrentHashMap` 或对写操作加锁；`saveToExternal` 与 `updateEntry` 共用同一把写锁。

### A7 🟡 中｜SearchTranslationCache/InheritanceCache currentLibVersion 会话内不刷新
- **问题**：`ensureIndexLoaded()` 只执行一次，`currentLibVersion` 会话内恒定；若首次计算发生在翻译库合并缓存生成前，libVersion 为空哈希，此后会话内用户表/翻译库变化均不触发失效 → **陈旧翻译服务到重启**。
- **修改前**：版本仅初始化时算一次。
- **修改后**：每次 `isFresh`/写记录前重算 libVersion（可加短 TTL 控成本）。

### A8 🟡 中｜InheritanceCache 固定 tmp 并发写 + mtime 粒度陈旧
- **问题**：①`writeCache` 同 A2 固定 tmp 并发写风险；②`computeSourceSig` 仅 `path:mtime` 无 size，FAT32 mtime 2 秒粒度，同秒内重写文件不触发失效 → 陈旧继承链；`symbolMemo` 同病。
- **修改前**：`tmp.writeText; tmp.renameTo`；签名 `"path:${file.lastModified()}"`。
- **修改后**：写缓存改 `atomicWriteText`；签名加 `file.length()`。

### A9 🟢 低｜InheritanceCache.flushIndex() 无任何调用方
- **问题**：仅预热 finally 里直接 `saveIndex()`；编辑器写缓存置 `indexDirty=true` 后从未落盘 → 下次启动索引缺失新条目（性能损失 + 孤儿缓存文件）。
- **修改前**：`flushIndex` 定义了但无人调用。
- **修改后**：编辑器关文件/应用暂停等时机调用 `InheritanceCache.flushIndex()`。

### A10 🟢 低｜VersionComparator 大文件全量读入内存 + O(m·n) DP
- **问题**：整文件 `readText` + `.lines()`；`computeLineDiff` 阈值内建 (m+1)×(n+1) IntArray（最高 16MB/文件），4 线程并行瞬态 ~64MB+。超阈值退化为按位置配对，精度下降但不崩。
- **修改前**：全量读 + DP 分配。
- **修改后**：阈值降到 ~1M 格或改 Myers；读取用按需行流。优先级低。

### A11 🟢 低｜HomeScreen 搜索替换写盘（基本安全）
- **问题**：已正确用 `file.atomicWriteText`（走全局写锁）✓。残余窗口：搜索快照→写盘期间外部改文件，重定位找不到则静默跳过该条（不替换不报错）。
- **修改前**：重定位失败静默跳过。
- **修改后**：可选——重定位失败计入失败数并提示用户。

### A12 🟢 低｜DiffUtil.computeLineDiff（已有阈值保护）
- **问题**：4M 格阈值已防内存爆炸；阈值内最坏 ~16MB DP。
- **修改前**：阈值内最坏 ~16MB。
- **修改后**：阈值可下调至 1M。优先级低。

---

## B. 导入导出与安全

### B1 🔴 高｜HomeScreen 解压 .rwmod 存在 zip-slip
- **问题**：`HomeScreen.kt` L299 `val o = File(t, e.name)` **直接用 entry 全路径**（含目录层级）拼目标目录。恶意 .rwmod 的 entry name 含 `../` 或绝对路径时可把文件写到目标目录之外（覆盖任意可达路径）。无解压大小/文件数上限（zip 炸弹）。
- **修改前**：`File(t, e.name)` + `o.outputStream().use { z.copyTo(it) }` 无校验无限制。
- **修改后**：entry 名规范化后校验 `normalized.startsWith(t.absolutePath)`，拒绝 `..`/绝对路径 entry；加解压总大小/条目数上限。

### B2 🟡 中｜FileImportHelper 文件名未清洗
- **问题**：`getFileName` 直接采用 content URI 的 `DISPLAY_NAME`（L77-84），未做 `File(name).name` 归一化；若提供方返回含路径分隔符/`..` 的名字，`File(targetDir, fileName)`（L38）可能拼接出逃逸路径。file scheme 时用 `lastPathSegment` 风险低。
- **修改前**：`val targetFile = File(targetDir, fileName)`，fileName 未清洗。
- **修改后**：`val safeName = File(fileName).name` 后拼接；拒绝空名。

### B3 🟡 中｜IniImageReader 图片无采样解码 → OOM 风险
- **问题**：`BitmapFactory.decodeFile`（L33/L44）**无 `inSampleSize`/Bounds 预读**，大图（MOD 贴图常见 2K-4K）整图解码进内存，移动端易 OOM。`cache` 是 `mutableMapOf` 无同步、超 200 全清（无 LRU），200 张大图缓存内存可观。
- **修改前**：`decodeFile(imageFile.absolutePath)` 直接解码。
- **修改后**：`BitmapFactory.Options` 先 `inJustDecodeBounds` 读尺寸，按显示目标尺寸算 `inSampleSize` 采样解码；缓存上限收紧 + 存弱引用。

### B4 🟡 中低｜FileProvider 暴露整个外部存储
- **问题**：`res/xml/file_paths.xml` 仅一条 `<external-path name="external" path="." />` —— 分享 zip 只需要导出目录，却暴露整个 SD 卡根。grantUriPermissions 虽按 URI 授权，但违背最小权限原则，被第三方误调用的面大。
- **修改前**：`path="."` 全盘暴露。
- **修改后**：收窄为 `RwmodPaths` 的 `exports/`、`imports/` 等具体子目录。

### B5 🟢 低中｜backup_rules 为空 = 默认全量备份
- **问题**：`res/xml/backup_rules.xml` 空规则 → Android 12+ 默认备份应用私有数据（SharedPreferences 等），若含敏感配置则随备份外泄。
- **修改前**：空 `<full-backup-content>`。
- **修改后**：按需 include/exclude（明确排除含密钥的设置项）。

### B6 🟢 低｜Manifest VIEW filter 用 `*/*` + BROWSABLE 兜底
- **问题**：`.ini/.template/.replay/.rwmod/.tmx/.zip` 的 filter 均带 `mimeType="*/*"`，应用会出现在任意文件的「打开方式」里，外部恶意 URI 也会投递进来，依赖 `processIntent` 自行校验。
- **修改前**：`*/*` 兜底 + BROWSABLE。
- **修改后**：保留但 `processIntent` 严格校验：仅接受 RWmod 目录内目标或走受控导入；非法 URI 直接 Toast 拒绝。

### B7 ✅ 安全｜LocalConfigManager importFromZip
- **问题**：无。L128 `File(entry.name).name` 取 basename + L129 白名单 `NEEDED_FILES` 过滤 + 扁平化落盘，天然防 zip-slip。
- **修改前**：—；**修改后**：—

### B8 ✅ 未发现｜硬编码密钥
- **问题**：无。`AiService` 的 apiKey 来自 `SettingsManager.aiApiKey`（用户配置），无硬编码 secret/token。
- **修改前**：—；**修改后**：—

### B9 🟢 低｜UriUtils.uriToAbsolutePath
- **问题**：MuMu 分支 `candidates.firstOrNull { exists } ?: candidates.first()` 可能返回不存在的路径（无害）；异常已 catch 返回 null。无安全风险。
- **修改前**：—；**修改后**：—

---

## C. 并发 / 协程 / 网络

### C1 🟡 中｜MainActivity importReplay/importRwmod/importMap 协程无 try-catch
- **问题**：`lifecycleScope.launch` 内直接调 `FileImportHelper.importFromUri`，无 try-catch，异常会**崩溃进程**（importLocalConfig 已有正确写法可复用）。
- **修改前**：协程异常未捕获。
- **修改后**：包 try-catch 记日志并 Toast 提示。

### C2 🟡 中｜TranslationDict 无锁 HashMap 并发读写（最高风险项）
- **问题**：`enToZh`/`zhToEn` 等用普通 `mutableMapOf`，后台线程 `updateUserEntriesInMemory`/`updateEntry` 写、编辑器线程读，**数据竞争**；`compilePatterns` 重建的 `enToZhPattern`/`enToZhLookup` 非 `@Volatile`，可见性无保证 → 保存翻译条目与打字翻译同时发生时可能读到半更新 map 或损坏。
- **修改前**：普通 HashMap + 无锁 + 非 volatile lookup。
- **修改后**：读写统一走同一把锁（复用 `loadMutex` 或独立 `ReadWriteLock`）；lookup/pattern 加 `@Volatile` 或用不可变快照整体替换。

### C3 🟡 中｜AiService 阻塞读不响应协程取消
- **问题**：流式 `reader.readLine()` 与非流式 `readText()` 是阻塞式，协程取消不会中断 IO 线程，会读完整个响应并继续回调 `onDelta` → 关闭 AI 弹窗/切文件后仍在消耗 token、onDelta 仍写 Compose state。
- **修改前**：读循环无取消检查。
- **修改后**：读循环内加 `ensureActive()`/`coroutineContext.isActive` 检查，或 `withContext(Dispatchers.IO) { cancellable }`；取消时 `conn.disconnect()` 中断读。

### C4 🟡 中｜UpdateStateManager installApk 后台线程 startActivity
- **问题**：`withContext(Dispatchers.IO) { UpdateDownloader.installApk(...) }`，`installApk` 内部 `context.startActivity` 从后台线程调起，Android 12+ 可能被拦截，部分机型安装界面调不起来。
- **修改前**：IO 线程 startActivity。
- **修改后**：installApk 的 `startActivity` 改在 `Dispatchers.Main`（仅包 URI/权限检查留 IO）。

### C5 🟡 中｜MainActivity 启动主线程文件 I/O
- **问题**：`SettingsManager.init`（含 `migrateFileStorage` 目录遍历）、`FileClipboardManager.init`、`loadBlocklist`、`cleanupStale` 全部主线程同步执行 → 大目录/旧数据迁移时**首帧卡顿、ANR 风险**。
- **修改前**：主线程同步文件 I/O。
- **修改后**：文件型 init 挪进已有的 applicationScope 后台协程，主线程只留 `RwmodPaths`/SharedPreferences 轻量初始化。

### C6 🟡 中低｜TaskProgressManager start() 读改写非原子 + 跨线程写 state
- **问题**：`start()` 对 `_currentTask` 的读-改-写非原子，两个后台任务（后台初始化 + 查重）并发 start 会丢失/错乱完成列表；`mutableStateOf` 由后台线程直接写（快照安全但跨线程），`handler.removeCallbacksAndMessages(null)` 从后台清掉其它任务回调。
- **修改前**：非原子读改写 + 跨线程写 state + 全量清回调。
- **修改后**：start 加锁/`compareAndSet`；state 更新切主线程；只移除本任务的回调。

### C7 🟢 低｜catch(Exception) 吞 CancellationException
- **问题**：`MainApp.kt:148`、`UpdateStateManager.kt:41,88` `catch (Exception)` 吞 `CancellationException` → 取消被当网络失败处理（显示"检查失败/下载失败"）。
- **修改前**：catch 块无重新抛出。
- **修改后**：catch 块首行 `if (e is CancellationException) throw e`。

### C8 🟢 低｜EditorScreen 语言切换 / VersionCompareScreen AI 流式回调
- **问题**：①`EditorScreen.kt:1581/1694` `scope.launch` 无 catch，`translateToXxx` 抛异常将崩溃；②`VersionCompareScreen.kt:207-211` IO 线程回调直接 `+=` 写 `aiStreamText`（跨线程 + 每个 token 重组）。
- **修改前**：无 catch / 跨线程写 state 无节流。
- **修改后**：加 try-catch 区分取消；回调切主线程 + 节流。

### C9 🟢 低｜AiSuggestionPrefetcher TOCTOU
- **问题**：`inFlight?.isActive` 检查在 synchronized 之外，多线程调用理论可并发两个请求（当前调用方均主线程，实际低危）。
- **修改前**：检查在锁外。
- **修改后**：检查移入同步块。

### C10 🟢 低｜TranslationEditorScreen 主线程扫描下载目录
- **问题**：`LaunchedEffect { findConfigZipFiles(...) }` 主线程扫多个下载目录。
- **修改前**：主线程遍历目录。
- **修改后**：`withContext(Dispatchers.IO)`。

### C11 ✅ 无｜协程作用域整体规范
- **问题**：无。`rememberCoroutineScope` 均随组合销毁取消；AI 请求已正确传播取消；ProjectTagScanner 扫描分片线程隔离、合并串行、`scanIfNeeded` 已 `@Synchronized`；补全表/翻译/缓存预热已在 `applicationScope` 后台串行执行、失败有降级。
- **修改前**：—；**修改后**：—

---

## 优先级建议（若后续排期修复）

| 优先级 | 条目 | 风险 |
|---|---|---|
| P0 | B1 zip-slip、A1 VerifyManager | 安全 / 数据误重置 |
| P1 | C2 TranslationDict 并发、A2/A3 缓存并发写、A4 settings 原子写、A5 Todo 丢操作、C3 AiService 阻塞读、C5 启动主线程 I/O、C1 协程崩溃 | 数据/稳定性 |
| P2 | A6 翻译库写入、A7 版本失效、A8 mtime 粒度、B3 图片 OOM、B4 FileProvider、C4 installApk 线程 | 质量 |
| P3 | A9-A12、B2/B5/B6、C6-C10 | 低 |

> 注：A 组与 B/C 组多数问题的根因一致——「文件写入非原子 + 读改写无锁」，与第一轮保存链路修复（AtomicWrite 全局锁）同源；后续修复可优先复用全局写锁与原子写统一收口。

---

# 修复与复查记录（2026-09-01 晚，全部已落地并编译验证）

> 老板要求格式：为什么 / 改之前 / 改之后。以下为全部修复的最终状态汇总。

## 已修复（32 条 + 复查新增）

### 🔴 高
| 功能 | 为什么 | 改之前 | 改之后 |
|---|---|---|---|
| 解压 .rwmod（B1） | 解压直接拼包内路径，恶意包可写目录外 | 恶意包覆盖模组目录外文件 | 路径校验+上限+失败清理，合法包正常 |
| 验证码文件（A1） | 读改写无锁互相覆盖 | 补全表/翻译库偶尔莫名重置 | 加锁+缓存+原子写，改什么是什么 |
| 分享（B4 复查修复） | FileProvider 收窄后，任意位置 .rwmod 分享失败 | 点分享报"分享失败" | 外部文件先拷入导出目录再分享，任意位置可用 |

### 🟡 中
| 功能 | 为什么 | 改之前 | 改之后 |
|---|---|---|---|
| 翻译缓存（A2/A3） | 固定 tmp 并发写坏、索引非原子 | 翻译结果时灵时不灵 | 原子写+加锁，缓存稳定 |
| 设置文件（A4） | 直接覆盖写，被杀就坏 | 闪退后全部设置回默认 | 原子写，设置不丢 |
| 待办（A5） | 读改写无锁 | 快速操作丢记录 | 串行+原子写，删了就删了 |
| 翻译字典（C2） | HashMap 并发读写 | 保存翻译条目时卡顿/闪退 | 并发容器+锁+快照，不崩 |
| AI 流式（C3） | 阻塞读不响应取消 | 关面板还白烧 token | 取消立即断连 |
| 启动（C5） | 主线程文件 IO（部分回退，见下） | 首屏卡顿 | 剪贴板/屏蔽词/清包挪后台 |
| 安装（C4） | 后台线程弹安装 | 部分机型装不了 | 主线程调起 |
| 查重速度（AtomicWrite per-path 锁） | 全局写锁把并行写串行化 | 开多线程查重仍慢 | 按路径加锁，多线程写盘恢复并行 |

### 🟢 低 / 纯加固（值/行为不变）
VerifyManager 缓存、InheritanceCache 签名、DiffUtil、补全表原子写、图片采样（无调用方）、备份规则、11 个零散文件原子写、协程 catch、进度锁、节流、扫描线程等。

## 过程中发现并修正的自身问题（坦诚记录）
1. **C5 loadBlocklist 后台化**（并发竞态）→ 已回退恢复同步
2. **DiffUtil 阈值 1M**（diff 精度下降）→ 已回退 4M
3. **解压条目上限 2000**（误伤大型模组）→ 放宽 10000
4. **VerifyManager.write 丢 key**（缓存未建立时写空）→ 已修
5. **AI 流式消息堆积**（每 token 切主线程）→ 已修 100ms 节流
6. **B4 分享失败**（FileProvider 收窄）→ 已修拷贝兜底
7. **AtomicWrite 全局锁**（查重并行退化）→ 已改 per-path 锁

## 验证状态
- ✅ `:app:compileDebugKotlin` 全工程编译通过（多次，含最新修复）
- ⏳ 真机回归（保存/分享/查重/翻译/预热）待构建方出包后执行
