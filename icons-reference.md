# 铁锈模组编辑器 (RustedWarfareModStudio) 图标速查表

> 基于 Compose Material Icons Extended，导入方式：
> ```kotlin
> import androidx.compose.material.icons.Icons
> import androidx.compose.material.icons.filled.*
> import androidx.compose.material.icons.outlined.*
> import androidx.compose.material.icons.automirrored.filled.*
> ```

---

## 一、文件与项目管理

| 功能 | 图标 | Compose 代码 |
|------|------|-------------|
| 新建文件 | Create | `Icons.Default.Create` |
| 打开文件/文件夹 | FolderOpen | `Icons.Default.FolderOpen` |
| 保存 | Save | `Icons.Default.Save` |
| 另存为 | SaveAs | `Icons.Default.SaveAs` |
| 导入 | FileDownload | `Icons.Default.FileDownload` |
| 导出 | FileUpload | `Icons.Default.FileUpload` |
| 关闭文件 | Close | `Icons.Default.Close` |
| 文件列表 | Folder | `Icons.Default.Folder` |
| 最近打开 | History | `Icons.Default.History` |
| 删除 | Delete | `Icons.Default.Delete` |

---

## 二、编辑操作

| 功能 | 图标 | Compose 代码 |
|------|------|-------------|
| 撤销 | Undo | `Icons.AutoMirrored.Filled.Undo` |
| 重做 | Redo | `Icons.AutoMirrored.Filled.Redo` |
| 剪切 | ContentCut | `Icons.Default.ContentCut` |
| 复制 | ContentCopy | `Icons.Default.ContentCopy` |
| 粘贴 | ContentPaste | `Icons.Default.ContentPaste` |
| 全选 | SelectAll | `Icons.Default.SelectAll` |
| 查找 | Search | `Icons.Default.Search` |
| 查找替换 | FindReplace | `Icons.Default.FindReplace` |
| 格式化代码 | FormatAlignLeft | `Icons.Default.FormatAlignLeft` |
| 代码美化 | AutoFixNormal | `Icons.Default.AutoFixNormal` |
| 注释/取消注释 | Code | `Icons.Default.Code` |

---

## 三、代码补全与提示

| 功能 | 图标 | Compose 代码 |
|------|------|-------------|
| 补全建议 | Lightbulb | `Icons.Default.Lightbulb` |
| 代码片段 | SnippetFolder | `Icons.Default.SnippetFolder` |
| 智能提示 | TipsAndUpdates | `Icons.Default.TipsAndUpdates` |
| 参数信息 | Info | `Icons.Default.Info` |
| 警告 | Warning | `Icons.Default.Warning` |
| 错误 | Error | `Icons.Default.Error` |

---

## 四、INI 文件结构（铁锈模组核心）

| 类型 | 图标 | Compose 代码 | 说明 |
|------|------|-------------|------|
| Section（区块） | AccountTree | `Icons.Default.AccountTree` | `[core]`、`[graphics]` 等区块 |
| Key（键） | VpnKey | `Icons.Default.VpnKey` | `name`、`displayText` 等键 |
| Value（值） | ShortText | `Icons.Default.ShortText` | 键对应的值 |
| Template（模板） | Star | `Icons.Default.Star` | 代码模板 |
| 布尔值 | ToggleOn | `Icons.Default.ToggleOn` | true/false |
| 数值 | Numbers | `Icons.Default.Numbers` | 数字类型值 |

---

## 五、模组资源类型

| 资源类型 | 图标 | Compose 代码 |
|---------|------|-------------|
| 单位（Unit） | Person | `Icons.Default.Person` |
| 建筑（Building） | Domain | `Icons.Default.Domain` |
| 科技（Tech） | Science | `Icons.Default.Science` |
| 图像（Image） | Image | `Icons.Default.Image` |
| 声音（Sound） | VolumeUp | `Icons.Default.VolumeUp` |
| 粒子效果 | Grain | `Icons.Default.Grain` |
| 动画 | Animation | `Icons.Default.Animation` |
| 武器 | Hardware | `Icons.Default.Hardware` |
| 投射物 | Rocket | `Icons.Default.Rocket` |
| 效果（Effect） | FlashOn | `Icons.Default.FlashOn` |

---

## 六、导航与视图

| 功能 | 图标 | Compose 代码 |
|------|------|-------------|
| 返回 | ArrowBack | `Icons.AutoMirrored.Filled.ArrowBack` |
| 前进 | ArrowForward | `Icons.AutoMirrored.Filled.ArrowForward` |
| 向上 | ArrowUpward | `Icons.Default.ArrowUpward` |
| 向下 | ArrowDownward | `Icons.Default.ArrowDownward` |
| 展开 | ExpandMore | `Icons.Default.ExpandMore` |
| 折叠 | ExpandLess | `Icons.Default.ExpandLess` |
| 菜单 | Menu | `Icons.Default.Menu` |
| 更多选项 | MoreVert | `Icons.Default.MoreVert` |
| 设置 | Settings | `Icons.Default.Settings` |
| 主页 | Home | `Icons.Default.Home` |

---

## 七、状态与反馈

| 状态 | 图标 | Compose 代码 |
|------|------|-------------|
| 成功/已保存 | Check | `Icons.Default.Check` |
| 已修改 | Edit | `Icons.Default.Edit` |
| 同步中 | Sync | `Icons.Default.Sync` |
| 加载中 | HourglassTop | `Icons.Default.HourglassTop` |
| 已完成 | CheckCircle | `Icons.Default.CheckCircle` |
| 需要帮助 | Help | `Icons.Default.Help` |
| 信息 | Info | `Icons.Default.Info` |

---

## 八、工具与功能

| 功能 | 图标 | Compose 代码 |
|------|------|-------------|
| 预览 | Preview | `Icons.Default.Preview` |
| 运行/测试 | PlayArrow | `Icons.Default.PlayArrow` |
| 调试 | BugReport | `Icons.Default.BugReport` |
| 构建 | Build | `Icons.Default.Build` |
| 终端/控制台 | Terminal | `Icons.Default.Terminal` |
| 对比 | CompareArrows | `Icons.Default.CompareArrows` |
| 合并 | Merge | `Icons.Default.Merge` |
| 分支 | AccountTree | `Icons.Default.AccountTree` |

---

## 九、文件类型图标（编辑器标签页）

| 文件类型 | 图标 | Compose 代码 |
|---------|------|-------------|
| INI 文件 | Description | `Icons.Default.Description` |
| 图片文件 | Image | `Icons.Default.Image` |
| 音频文件 | Audiotrack | `Icons.Default.Audiotrack` |
| 文本文件 | Article | `Icons.Default.Article` |
| 脚本文件 | Code | `Icons.Default.Code` |
| 配置文件 | Settings | `Icons.Default.Settings` |
| JSON 文件 | DataObject | `Icons.Default.DataObject` |
| 压缩包 | FolderZip | `Icons.Default.FolderZip` |

---

## 十、底部状态栏推荐图标

```kotlin
// 行号统计
Icon(Icons.Default.Label, contentDescription = null)  // 或 FormatLineSpacing

// 字符统计
Icon(Icons.Default.ShortText, contentDescription = null)

// 编码格式
Icon(Icons.Default.Code, contentDescription = null)

// 光标位置
Icon(Icons.Default.Place, contentDescription = null)
```

---

## 使用建议

### 1. 统一图标大小规范
```kotlin
// 工具栏图标
modifier = Modifier.size(24.dp)

// 列表/补全项图标
modifier = Modifier.size(16.dp)

// 状态栏小图标
modifier = Modifier.size(14.dp)

// 标题栏文件图标
modifier = Modifier.size(20.dp)
```

### 2. 颜色搭配建议
```kotlin
// 主色调图标
tint = RustedPrimary

// 禁用状态
tint = Color.White.copy(alpha = 0.5f)

// 强调色（可点击/已修改）
tint = RustedAccent

// 次要信息
tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
```

### 3. 图标变体选择
- **Filled（实心）**：用于主要操作按钮、选中状态
- **Outlined（轮廓）**：用于未选中状态、次要操作
- **Rounded（圆角）**：用于更柔和的 UI 风格

---

## 常见问题

### Q: 图标找不到编译错误？
确保已添加 extended 依赖：
```kotlin
implementation("androidx.compose.material:material-icons-extended")
```

### Q: 某些图标在 `Icons.Default` 中找不到？
部分图标可能在其他包中：
```kotlin
// 自动镜像图标（有方向的）
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note

// 轮廓风格
import androidx.compose.material.icons.outlined.Settings

// 圆角风格
import androidx.compose.material.icons.rounded.Home
```

### Q: 图标太多导致包体积增大？
可以只导入需要的图标，替代通配符导入：
```kotlin
// 不推荐（导入所有图标）
import androidx.compose.material.icons.filled.*

// 推荐（只导入需要的）
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
```
