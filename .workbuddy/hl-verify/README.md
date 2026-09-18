# 自定义高亮规则离线对账工具（hl-verify）

用**编译产物里的真实生产代码**生成「内置复刻规则 → 合并语法」，再与内置语法逐条比对，
最后用真实正则引擎（joni + 项目 jcodings 编码表）在语料上验证命中是否等价。

## 跑法

```bash
JDK=<项目>/tools/jdk/jdk-17.0.19+10/bin
KC=<项目>/app/build/tmp/kotlin-classes/debug          # 先 ./gradlew assembleDebug 生成
CP="$KC;kotlin-stdlib-2.2.0.jar;kotlinx-serialization-core-jvm-1.7.1.jar;\
kotlinx-serialization-json-jvm-1.7.1.jar;joni-2.2.7.jar;<项目>/app/libs/jcodings-1.0.63-noreader.jar"

javac -encoding UTF-8 -cp "$CP" -d out $(find src -name "*.java")
java -Dfile.encoding=UTF-8 -Dtables=<项目>/app/src/main/assets/tables -cp "out;$CP" \
     Verify <项目>/app/src/main/assets/textmate/ini/ini.tmLanguage.json [设备规则文件.json]
```

不传第二个参数 = 用内置复刻 defaults；传了 = 走 `parseRules` 回读设备上的规则文件。

## 环境坑

- `app/libs/jcodings-1.0.63-noreader.jar` **不含** `org.jcodings.util.ArrayReader`
  （App 里由 `app/src/main/java/org/jcodings/util/ArrayReader.java` 影子实现，从 assets 读表），
  所以 `src/org/jcodings/util/ArrayReader.java` 是 JVM 版替身，必须一起编译；否则 `\b` 一遇中文就
  `NoClassDefFoundError`。
- `src/org/json/*` 是极简 org.json 替身（Kotlin 生产代码要用它解析 grammar）。
- `src/android/**` 与 `src/androidx/**`、`src/io/**`、`src/org/eclipse/**` 只是反射解析方法签名用的空壳类。
- Kotlin `internal` 函数在 JVM 上带 `$app_debug` 后缀（如 `buildMergedGrammarRaw$app_debug`）。
- 写文件时别在源码里落 NUL 字符：`" "` 曾被写成 `"\0"`，导致 key 拼接对不上（排查了半天）。

## 语料

`corpus_all.txt` = 从设备 `/sdcard/RWmod` 下真实 ini 抽出的唯一行（2107 行），加代码里 73 行人造样本。
拉取方式（中文路径别过 Windows 命令行，让设备端自己 cat）：

```
adb exec-out 'find /sdcard/RWmod -name "*.ini" | head -60 | xargs cat' > corpus_all.txt
```

**必须同时看对照实验输出**：脚本会把合并结果退化成「改前」的 ASCII 边界再比一次，
如果对照也是 0 不一致，说明语料没覆盖到、结论不可信。当前：改后 0 条 / 对照 17 条 → 语料有区分力。

## 输出

- 复刻对账（内置 92 条带 scope 规则 vs 92 个槽位）
- 合并替换条数、新增手写规则条数
- 每条被替换的正文：内置 vs 合并，用 joni 在语料上比对（无 captures 的规则只比整体命中，
  因为内置 `(a|b)` 多出的未命名捕获组不参与上色）
- 定位对账：复刻端 (原文,份号) 键 vs 合并端扫描算出的键 → 差集 = 改了不生效的组
