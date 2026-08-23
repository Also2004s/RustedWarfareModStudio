package com.rwmodstudio.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.translation.ResourceNameKeys
import com.rwmodstudio.core.translation.TranslationEngine
import com.rwmodstudio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 资源名补全属性名单维护页（开发者工具）。
 *
 * 决定哪些属性在冒号后触发「资源名=值」的资源名补全。名单以英文存储，
 * 可直接增删、从翻译库选择、重置默认。
 */
@Composable
fun ResourceNameKeysScreen() {
    val context = LocalContext.current
    var keys by remember { mutableStateOf(ResourceNameKeys.DEFAULT_KEYS) }
    var loaded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    // 进入页面即加载真实配置
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cfg = ResourceNameKeys.load()
            keys = cfg.keys
            loaded = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RustedBackground)
    ) {
        // 顶部说明 + 操作
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "命中名单的属性在冒号后弹资源名补全。支持 mutatorX_ 通配。",
                fontSize = 11.sp,
                color = RustedOnBackground.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f)
            )
            if (loaded) {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Default.Add, "从翻译库选择", tint = RustedPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RustedPrimary)
            }
        } else {
            // 名单列表
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(keys.size) { idx ->
                    val key = keys[idx]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(RustedSurface)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            key,
                            fontSize = 13.sp,
                            color = RustedOnBackground,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val newList = keys.toMutableList().apply { removeAt(idx) }
                                keys = newList
                            },
                            modifier = Modifier.size(24.dp)
                        ) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = RustedError) }
                    }
                }
            }

            // 底部操作按钮
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    ResourceNameKeys.reset()
                    keys = ResourceNameKeys.DEFAULT_KEYS
                    Toast.makeText(context, "已重置为默认名单", Toast.LENGTH_SHORT).show()
                }) { Text("重置默认", color = RustedError) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val ok = ResourceNameKeys.save(keys)
                        Toast.makeText(context, if (ok) "保存成功" else "保存失败", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RustedPrimary)
                ) { Text("保存") }
            }
        }
    }

    // 从翻译库/代码参考库选择名单项
    if (showPicker) {
        var search by remember { mutableStateOf("") }
        var candidates by remember { mutableStateOf(listOf<String>()) }
        var loading by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val engine = TranslationEngine.getInstance()
                if (!engine.isLoaded) engine.load(context)
                // 候选来源：翻译库英文 key + 代码参考库各节属性的 name_en，去重并按 mutator 变体归一化
                val dictEn = runCatching { engine.getTranslationDict().getAllEnglishKeys() }.getOrDefault(emptyList())
                val codeRefEn = runCatching {
                    val repo = com.rwmodstudio.core.translation.CodeReferenceRepository()
                    repo.loadFromAssets(context)
                    repo.getAllSectionNames()
                        .flatMap { repo.getPropertiesForSection(it) }
                        .mapNotNull { it.name_en }
                }.getOrDefault(emptyList())
                val pool = (dictEn + codeRefEn)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { key ->
                        if (key.startsWith("mutator", ignoreCase = true)) {
                            ResourceNameKeys().normalizedKey(key)
                        } else key
                    }
                    .distinct().sorted()
                candidates = pool
                loading = false
            }
        }
        val knownNorm = remember(keys) { keys.map { ResourceNameKeys().normalizedKey(it) }.toSet() }
        val available = remember(candidates, search, knownNorm) {
            candidates.filter { it !in knownNorm && (search.isBlank() || it.contains(search, ignoreCase = true)) }
        }

        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("从翻译库选择资源名属性") },
            text = {
                Column(Modifier.height(400.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("搜索英文名...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = RustedOnBackground.copy(alpha = 0.4f)) },
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("可选择 ${available.size} 项", fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.weight(1f)) {
                        if (loading) {
                            CircularProgressIndicator(color = RustedPrimary, modifier = Modifier.align(Alignment.Center))
                        } else if (available.isEmpty()) {
                            Text("没有可添加的项", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.4f), modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn {
                                items(available) { key ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                keys = (keys + key).distinct()
                                                showPicker = false
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(16.dp), tint = RustedPrimary)
                                        Spacer(Modifier.width(6.dp))
                                        Text(key, fontSize = 12.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("关闭") }
            }
        )
    }
}