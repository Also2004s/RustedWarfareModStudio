package com.rwmodstudio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.rwmodstudio.ui.theme.*

/**
 * 统一的底部菜单弹窗容器。
 * 顶部带拖拽指示条，内容区域使用圆角卡片背景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuBottomSheet(
    onDismissRequest: () -> Unit,
    title: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RustedSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                Icon(Icons.Default.DragHandle, null, Modifier.size(32.dp), tint = RustedOnBackground.copy(alpha = 0.25f))
                if (title.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = RustedOnBackground)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            content()
        }
    }
}

/**
 * 底部菜单项：左侧大图标（圆形主题色背景）+ 标题/描述。
 */
@Composable
fun MenuSheetItem(
    icon: ImageVector,
    title: String,
    description: String = "",
    iconTint: Color = RustedPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground)
            if (description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(description, fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.5f))
            }
        }
    }
}

/**
 * 分组标题 + 分隔线。
 */
@Composable
fun MenuSheetGroup(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.08f))
    }
}

/**
 * 底部弹窗中的开关项。
 */
@Composable
fun MenuSheetSwitch(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    iconTint: Color = RustedPrimary,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
        }
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RustedSurface,
                checkedTrackColor = RustedPrimary,
                uncheckedThumbColor = RustedSurface,
                uncheckedTrackColor = RustedOnBackground.copy(alpha = 0.25f)
            )
        )
    }
}

/**
 * 底部弹窗中的滑块项。
 */
@Composable
fun MenuSheetSlider(
    icon: ImageVector,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 10f..24f,
    valueLabel: String = "${value.toInt()}",
    iconTint: Color = RustedPrimary,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground, modifier = Modifier.weight(1f))
                Text(valueLabel, fontSize = 13.sp, color = RustedPrimary, fontWeight = FontWeight.Medium)
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = RustedPrimary,
                    activeTrackColor = RustedPrimary,
                    inactiveTrackColor = RustedOnBackground.copy(alpha = 0.15f)
                )
            )
        }
    }
}

/**
 * 适用于 DropdownMenu 内部的小号菜单项。
 * 保持原生前紧凑尺寸，左侧图标加小圆形主题色背景。
 */
@Composable
fun CompactMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    iconTint: Color = RustedPrimary,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(14.dp), tint = iconTint)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, fontSize = 13.sp, color = RustedOnBackground)
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.5f))
                    }
                }
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    )
}

/**
 * DropdownMenu 内部的开关项。
 */
@Composable
fun CompactMenuSwitch(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    iconTint: Color = RustedPrimary,
    onCheckedChange: (Boolean) -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(14.dp), tint = iconTint)
                }
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 13.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.height(20.dp).width(34.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RustedSurface,
                        checkedTrackColor = RustedPrimary,
                        uncheckedThumbColor = RustedSurface,
                        uncheckedTrackColor = RustedOnBackground.copy(alpha = 0.25f)
                    )
                )
            }
        },
        onClick = { onCheckedChange(!checked) },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    )
}

/**
 * DropdownMenu 内部的滑块项。
 */
@Composable
fun CompactMenuSlider(
    icon: ImageVector,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 10f..24f,
    valueLabel: String = "${value.toInt()}",
    iconTint: Color = RustedPrimary,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(14.dp), tint = iconTint)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 13.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
            Text(valueLabel, fontSize = 11.sp, color = RustedPrimary, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = RustedPrimary,
                activeTrackColor = RustedPrimary,
                inactiveTrackColor = RustedOnBackground.copy(alpha = 0.15f)
            )
        )
    }
}

/**
 * 简洁风格的 DropdownMenu 菜单项：纯线条图标 + 文字，无圆形彩色背景。
 */
@Composable
fun PlainMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    tint: Color = RustedOnBackground,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = tint)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontSize = 13.sp, color = RustedOnBackground, maxLines = 1, softWrap = false)
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, fontSize = 10.sp, color = RustedOnBackground.copy(alpha = 0.5f), maxLines = 1, softWrap = false)
                    }
                }
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(start = 14.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
    )
}

/**
 * 简洁风格的 DropdownMenu 开关项：纯线条图标 + 文字 + Switch。
 */
@Composable
fun PlainMenuSwitch(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    tint: Color = RustedOnBackground,
    onCheckedChange: (Boolean) -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = tint)
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 13.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.height(20.dp).width(34.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RustedSurface,
                        checkedTrackColor = RustedPrimary,
                        uncheckedThumbColor = RustedSurface,
                        uncheckedTrackColor = RustedOnBackground.copy(alpha = 0.25f)
                    )
                )
            }
        },
        onClick = { onCheckedChange(!checked) },
        contentPadding = PaddingValues(start = 14.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
    )
}

/**
 * 简洁风格的 DropdownMenu 滑块项：纯线条图标 + 文字 + 数值 + Slider。
 */
@Composable
fun PlainMenuSlider(
    icon: ImageVector,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 10f..24f,
    valueLabel: String = "${value.toInt()}",
    tint: Color = RustedOnBackground,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(start = 14.dp, end = 20.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 13.sp, color = RustedOnBackground, modifier = Modifier.weight(1f))
            Text(valueLabel, fontSize = 11.sp, color = RustedPrimary, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = RustedPrimary,
                activeTrackColor = RustedPrimary,
                inactiveTrackColor = RustedOnBackground.copy(alpha = 0.15f)
            )
        )
    }
}

/**
 * 悬浮菜单定位器：弹窗出现在锚点下方、右对齐；下方空间不足时翻到上方。
 */
private class BelowAnchorEndProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        var x = anchorBounds.right - popupContentSize.width
        var y = anchorBounds.bottom + 4
        if (y + popupContentSize.height > windowSize.height - 8) {
            y = anchorBounds.top - popupContentSize.height - 4
        }
        x = x.coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
        y = y.coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
        return IntOffset(x, y)
    }
}

/**
 * 统一的悬浮弹出菜单容器，使用圆角卡片 + 细边框，视觉风格与长按悬浮菜单一致。
 * 用法与 DropdownMenu 类似，放在需要触发菜单的 Box 内部即可。
 */
@Composable
fun StyledPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (expanded) {
        Popup(
            popupPositionProvider = BelowAnchorEndProvider(),
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)
        ) {
            Card(
                modifier = modifier,
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = RustedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                border = BorderStroke(0.5.dp, RustedOnBackground.copy(alpha = 0.10f))
            ) {
                Column(content = content)
            }
        }
    }
}
