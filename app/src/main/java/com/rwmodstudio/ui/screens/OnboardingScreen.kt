package com.rwmodstudio.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rwmodstudio.R
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.ui.theme.*
import com.rwmodstudio.util.uriToAbsolutePath
import java.io.File

private fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    val totalPages = 4

    // 权限状态会在从系统设置返回后刷新
    var storageGranted by remember { mutableStateOf(checkStoragePermission(context)) }
    var defaultPath by remember { mutableStateOf(SettingsManager.defaultModPath()) }
    var recentLimit by remember { mutableIntStateOf(SettingsManager.recentHistoryLimit) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        storageGranted = result.all { it.value }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        storageGranted = checkStoragePermission(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RustedBackground)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 90.dp)
        ) {
            // 右上角跳过（欢迎页和权限页显示）
            if (page < 2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            SettingsManager.defaultPath = defaultPath
                            SettingsManager.onboardingVerified = true
                            onFinished()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("跳过引导", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.6f))
                    }
                }
            }

            Crossfade(targetState = page, label = "onboarding_page") { target ->
                when (target) {
                    0 -> WelcomePage()
                    1 -> PermissionPage(
                        storageGranted = storageGranted,
                        defaultPath = defaultPath,
                        onDefaultPathChange = { defaultPath = it },
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                manageStorageLauncher.launch(intent)
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                )
                            }
                        }
                    )
                    2 -> RecentTutorialPage(
                        recentLimit = recentLimit,
                        onRecentLimitChange = {
                            recentLimit = it
                            SettingsManager.recentHistoryLimit = it
                        }
                    )
                    3 -> CustomCompletionTutorialPage()
                }
            }
        }

        // 底部导航
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(RustedBackground)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp, top = 12.dp)
        ) {
            // 页码指示器
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(totalPages) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == page) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == page) RustedPrimary
                                else RustedOnBackground.copy(alpha = 0.25f)
                            )
                    )
                    if (index < totalPages - 1) Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) {
                    TextButton(onClick = { page-- }) {
                        Text("上一步", fontSize = 14.sp, color = RustedOnBackground.copy(alpha = 0.7f))
                    }
                } else {
                    Spacer(Modifier.width(64.dp))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (page == totalPages - 1) {
                            SettingsManager.defaultPath = defaultPath
                            SettingsManager.onboardingVerified = true
                            onFinished()
                        } else {
                            page++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (page == totalPages - 1) "开始使用" else "下一步",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(RustedSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "应用图标",
                modifier = Modifier.size(80.dp),
                tint = androidx.compose.ui.graphics.Color.Unspecified
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "铁锈工坊",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = RustedPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "RW Mod Studio",
            fontSize = 15.sp,
            color = RustedOnBackground.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun PermissionPage(
    storageGranted: Boolean,
    defaultPath: String,
    onDefaultPathChange: (String) -> Unit,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("开始之前", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RustedOnBackground)
        Spacer(Modifier.height(4.dp))
        Text("完成以下设置即可开始使用", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.55f))
        Spacer(Modifier.height(24.dp))

        // 权限卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RustedSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(RustedPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            null,
                            tint = RustedPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("管理所有文件权限", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground)
                        Text(
                            "需要访问外部存储以读取和编辑模组文件",
                            fontSize = 12.sp,
                            color = RustedOnBackground.copy(alpha = 0.5f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRequestPermission,
                    enabled = !storageGranted,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (storageGranted) RustedSecondary.copy(alpha = 0.2f) else RustedPrimary,
                        disabledContainerColor = RustedSecondary.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (storageGranted) "已授权" else "去授权",
                        fontSize = 14.sp,
                        color = if (storageGranted) RustedSecondary else androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 默认目录卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RustedSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(RustedPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            null,
                            tint = RustedPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("设置默认工作目录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground)
                        Text(
                            "选择模组文件存放目录",
                            fontSize = 12.sp,
                            color = RustedOnBackground.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val context = LocalContext.current
                val folderPickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    uri?.let { uriToAbsolutePath(context, it) }?.let { onDefaultPathChange(it) }
                }
                val es = android.os.Environment.getExternalStorageDirectory()

                OutlinedTextField(
                    value = defaultPath,
                    onValueChange = onDefaultPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    label = { Text("工作目录", fontSize = 12.sp) }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { SettingsManager.defaultPath = defaultPath },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("保存", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val d = File(es, "rustedWarfare/units")
                            if (d.exists()) onDefaultPathChange(d.absolutePath)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("units", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("选择", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTutorialPage(
    recentLimit: Int,
    onRecentLimitChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("最近", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RustedOnBackground)
        Spacer(Modifier.height(4.dp))
        Text("随时找回你编辑过的文件", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.55f))
        Spacer(Modifier.height(16.dp))

        StepCard(
            icon = Icons.Default.History,
            title = "打开「最近」弹窗",
            description = "在首页、文件管理器或文本编辑器中，点击底部快捷栏的「最近」图标（时钟）。弹窗会从屏幕底部滑出。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Tab,
            title = "切换标签",
            description = "弹窗顶部有「最近打开」和「近期修改」两个标签。最近打开按时间排序；近期修改只显示保存过至少一次的文件。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Difference,
            title = "查看差异与跳转",
            description = "在「近期修改」中点击文件，可查看最近一次保存前后的差异。绿色为新增，红色为删除。点击某一行差异，可直接跳转到编辑器对应行。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Restore,
            title = "确认与回退",
            description = "「确认差异」会把当前内容设为新的基准，差异清空。「回退修改」会把文件恢复到最早一次保存前的内容。回退前请确保该文件没有打开编辑。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Storage,
            title = "调节缓存条数",
            description = "近期修改默认最多保存 100 条记录。下方滑块可调整范围（50 ~ 200），条数越多占用空间越大。"
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RustedSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("缓存条数：$recentLimit", fontSize = 13.sp, color = RustedOnBackground)
                Slider(
                    value = recentLimit.toFloat(),
                    onValueChange = { onRecentLimitChange(it.toInt()) },
                    valueRange = 50f..200f,
                    steps = 149,
                    colors = SliderDefaults.colors(
                        thumbColor = RustedPrimary,
                        activeTrackColor = RustedPrimary,
                        inactiveTrackColor = RustedPrimary.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }
}

@Composable
private fun CustomCompletionTutorialPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("自定义补全", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RustedOnBackground)
        Spacer(Modifier.height(4.dp))
        Text("添加你常用的属性或值", fontSize = 13.sp, color = RustedOnBackground.copy(alpha = 0.55f))
        Spacer(Modifier.height(16.dp))

        StepCard(
            icon = Icons.Default.Menu,
            title = "进入自定义补全",
            description = "在首页点击左上角的菜单图标打开侧边栏，选择「自定义补全」。页面分为「我的」「全部」「原生」「附件」四个标签。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Add,
            title = "添加一条补全",
            description = "点击顶部「添加」按钮。填写「名称」（例如：测试属性）、「默认值」（例如：true）、「说明」。英文名可留空，保存时会尝试从翻译库自动匹配。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Category,
            title = "选择分类与格式",
            description = "「补全分类」决定这条补全在哪些节里出现，例如核心、资源、AI，可多选。「格式分类」决定编辑器里补全时插入的内容：属性 → 名称:值；空值属性 → 名称:；values → 只插值；特定值 → 只插名称。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Save,
            title = "保存并使用",
            description = "点击「保存」后，中文用户表立即生效，英文用户表会自动生成，无需手动操作。回到编辑器，在对应节内输入名称，即可看到新补全。"
        )
        Spacer(Modifier.height(10.dp))
        StepCard(
            icon = Icons.Default.Edit,
            title = "管理已有补全",
            description = "在「我的」列表中点击编辑图标可修改，点击删除图标可移除。原生表和附件表由应用维护，不能直接编辑，但你可以通过添加同名用户条目来覆盖它们。"
        )
    }
}

@Composable
private fun StepCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RustedSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(RustedPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = RustedPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground)
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    fontSize = 12.sp,
                    color = RustedOnBackground.copy(alpha = 0.6f),
                    lineHeight = 17.sp
                )
            }
        }
    }
}
