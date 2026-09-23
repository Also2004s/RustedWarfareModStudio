package com.rwmodstudio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.ui.theme.RustedBackground
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSecondary
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// 编辑页「方块+」折叠菜单（输入法关闭时替代底部快捷栏使用）
// 视觉风格：圆角方块 + 按钮、逐条上浮弹出、每项独立悬浮（圆角矩形文字标签 + 独立圆形图标按钮）、
// 底部横栏 3 个小圆按钮位于 + 号左侧且与 + 垂直居中。
// ---------------------------------------------------------------------------

/** 「方块+」切换按钮：圆角方块，展开时「+」旋转 45° 变为「×」，不换图标。 */
@Composable
fun EditorFabButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "fabRotation"
    )
    // 与底部横栏其它圆形按钮完全一致的阴影方式：Surface 自带 shadowElevation = 2.dp
    // （轻量、贴合自身形状，无自绘阴影带、无渐变截断），方块本体旋转 45°
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(50.dp)
            .rotate(rotation),
        shape = RoundedCornerShape(14.dp),
        color = RustedBackground,
        contentColor = RustedOnBackground,
        shadowElevation = 2.dp
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (expanded) "收起" else "更多",
                modifier = Modifier.size(26.dp),
                // 「+」用反色（on-background），浅色主题下为深色、深色主题下为浅色
                tint = RustedOnBackground
            )
        }
    }
}

/** 逐条弹出动画：展开时从下往上浮出（竖向完整高度滑入 + 淡入），收起时反向向下退场。 */
@Composable
fun FabItemStagger(
    expanded: Boolean,
    index: Int,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(index * 45L)
            visible = true
        } else {
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) +
            slideInVertically(tween(220), initialOffsetY = { it }),
        exit = fadeOut(tween(90)) +
            slideOutVertically(tween(130), targetOffsetY = { it })
    ) {
        content()
    }
}

/** 悬浮条目：圆角矩形文字标签（最接近黑色的纯色底 + 阴影浮起）+ 独立圆形图标按钮，两者成对、右对齐，各为可点击区。 */
@Composable
fun EditorFabMenuRow(
    title: String,
    subtitle: String = "",
    icon: ImageVector,
    iconTint: Color = RustedOnBackground,
    onLabel: () -> Unit,
    onIcon: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            onClick = onLabel,
            shape = RoundedCornerShape(6.dp),
            color = RustedBackground,
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = RustedOnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.6f), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        // 独立圆形图标按钮（纯色实底）
        Surface(
            onClick = onIcon,
            shape = CircleShape,
            color = RustedBackground,
            contentColor = iconTint,
            shadowElevation = 2.dp
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = iconTint)
            }
        }
    }
}

/** 悬浮勾选项条目：可选空心方块复选框 + 圆角矩形文字标签 + 独立圆形图标按钮，与其它条目同构。 */
@Composable
fun EditorFabCheckRow(
    title: String,
    checked: Boolean,
    icon: ImageVector,
    iconTint: Color = RustedOnBackground,
    showCheck: Boolean = true,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        // 方块复选框（空心，可选显示）：勾选时填充主色 + 白色对勾
        if (showCheck) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (checked) RustedPrimary else Color.Transparent,
                border = BorderStroke(
                    1.5.dp,
                    if (checked) RustedPrimary else RustedOnBackground.copy(alpha = 0.45f)
                )
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (checked) {
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        // 圆角矩形文字标签（点击切换，跟随主题的表面反色）
        Surface(
            onClick = onToggle,
            shape = RoundedCornerShape(6.dp),
            color = RustedBackground,
            shadowElevation = 2.dp
        ) {
            Text(
                title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        // 独立圆形图标按钮（点击同样切换，纯色实底）
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = RustedBackground,
            shadowElevation = 2.dp
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = iconTint)
            }
        }
    }
}

/** 悬浮滑块条目：左侧文字标签仅显示当前实际值（如 14sp，无汉字说明）+ 右侧带阴影药丸的滑块。 */
@Composable
fun EditorFabSliderRow(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        // 左侧文字标签：仅显示实际数值，不显示汉字说明
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = RustedBackground,
            shadowElevation = 2.dp
        ) {
            Text(
                valueLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RustedOnBackground,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        // 紧凑悬浮滑块：药丸内「内部实心填充」样式（从左侧到当前滑块值用主题反色填充，随拖动实时变化）
        // 不使用 Material Slider（自带圆球无法彻底隐藏），改为自定义拖动控件：整个药丸区域可点按/拖动调节
        Box(
            Modifier
                .width(120.dp)
                .height(26.dp)
                .shadow(3.dp, RoundedCornerShape(13.dp), clip = false)
                .clip(RoundedCornerShape(13.dp))
                .background(RustedBackground)
                .pointerInput(valueRange) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + frac * (valueRange.endInclusive - valueRange.start))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + frac * (valueRange.endInclusive - valueRange.start))
                        }
                    )
                }
        ) {
            // 已填充部分：按当前值占全范围的比例实心填充左侧区域（主题青色）
            val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(RustedSecondary)
            )
        }
    }
}

/** 横向逐条弹出动画：展开时从右向左浮出（水平完整宽度滑入 + 淡入），收起时反向向右退场。 */
@Composable
fun FabRowItemStagger(
    expanded: Boolean,
    index: Int,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(index * 45L)
            visible = true
        } else {
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) +
            slideInHorizontally(tween(220), initialOffsetX = { it }),
        exit = fadeOut(tween(90))
    ) {
        content()
    }
}

/** 小号圆形图标按钮（底部横栏用，位于 + 号左侧）。 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(42.dp),
        shape = CircleShape,
        color = if (isActive) RustedSecondary else RustedBackground,
        contentColor = if (isActive) RustedSecondary else RustedOnBackground,
        shadowElevation = 2.dp
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(21.dp))
        }
    }
}
