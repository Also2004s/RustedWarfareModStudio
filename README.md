# 铁锈工坊 (RustedWarfare Studio)

为《铁锈战争》（Rusted Warfare）MOD 开发提供完整的移动端中文开发环境。Android 应用，包名 `com.rwmodstudio`。

## 功能特性

- **INI 编辑器** - 基于 sora-editor + TextMate 的代码编辑器，支持语法高亮、符号栏、查找替换、彩虹括号、行尾灯泡（强制翻译）
- **代码补全** - 节名/键名/值补全，值补全覆盖布尔、枚举、图片路径、内存、资源、标签、单位名、产生单位等类型；支持自定义补全表（原生表 + 用户表 + 附件表）与翻译库兜底
- **中英翻译** - 内置翻译引擎，支持中英文双向翻译、翻译查重、屏蔽词、文件翻译缓存
- **代码文档** - 基于代码参考库（`code_reference.json`）的属性说明与示例提示
- **项目辅助** - 文件管理、项目标签/全局标签/资源/内存扫描、文件继承链解析、版本对比（.ini/.template 差异）、坐标可视化、待办
- **编译打包** - 一键将项目文件夹打包为 `.rwmod` 文件，也支持解压 `.rwmod`
- **数据管理** - 本地配置导入/导出（zip），所有生成文件统一存储在外部存储 `RWmod/` 目录下，带验证码强制刷新机制

## 技术栈与构建要求

- Kotlin 2.2.0 + Jetpack Compose（Compose BOM 2024.06.00）
- Gradle 8.14.5 / Android Gradle Plugin 8.11.1 / JDK 17
- sora-editor 0.24.6（TextMate）
- minSdk 26（Android 8.0）/ targetSdk 34 / compileSdk 36

```powershell
# 本机构建使用仓库自带 JDK
$env:JAVA_HOME = "D:\ALSO2004\android-tool\RustedWarfareModStudio\tools\jdk\jdk-17.0.19+10"
.\gradlew.bat assembleDebug    # Debug（约 21MB）
.\gradlew.bat assembleRelease  # Release（R8 + 资源压缩，约 4.3MB，对外发布用）
```

## 项目结构

```
app/src/main/
├── assets/
│   ├── data/                  # 代码参考库、翻译库、片段、补全/翻译 raw 数据
│   ├── tables/                # jcodings 编码表（sora-editor 依赖，勿删）
│   └── textmate/              # 高亮主题 + INI grammar
└── java/com/rwmodstudio/
    ├── core/                  # 核心逻辑：路径、设置、验证码、保存历史、版本对比、继承链、标签扫描等
    │   └── translation/       # 翻译引擎、代码参考库、查重、屏蔽词等
    ├── editor/                # sora-editor 封装与 TextMate 初始化
    ├── feature/               # 补全（completion/，值补全按类型拆分）、坐标可视化（coord/）
    ├── ui/                    # 页面（screens/）、通用组件（components/）、主题（theme/）
    └── util/                  # IniImageReader、UriUtils、FileImportHelper
```

详细约定见仓库根目录 `AGENTS.md`。

## 数据与资源生成

- `tools/generate_translation_assets.py`：从外部权威源生成 `assets/data/translation.txt` 与附件翻译表
- `generate_icons.py`：从 `docs/icon_source.jpg` 生成各密度启动图标
- `subset_lxgw.py`：字体子集化

## 开发状态

当前版本 `1.2.0`（versionCode 11）。已完成：编辑器/语法高亮、代码补全、翻译引擎与查重、文件管理、`.rwmod` 打包/解压、设置与开发者模式、版本对比、继承链解析、项目标签扫描、坐标可视化、待办、本地配置导入/导出。
