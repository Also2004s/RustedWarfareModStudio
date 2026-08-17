package com.rwmodstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.SettingsManager
import com.rwmodstudio.editor.RainbowColorUtils
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import kotlin.math.roundToInt

/**
 * 彩虹括号参数设置面板，包含 4 色实时预览和所有可调参数。
 *
 * @param previewBackgroundColor 预览时使用的背景色（Int 0xAARRGGBB）
 * @param previewBaseColor 预览时使用的括号基础色（Int 0xAARRGGBR），默认金色
 */
@Composable
fun RainbowBracketSettingsPanel(
    previewBackgroundColor: Int,
    previewBaseColor: Int = 0xFFdeae12.toInt()
) {
    var hueStep by remember { mutableFloatStateOf(SettingsManager.rainbowHueStep) }
    var hueDirection by remember { mutableIntStateOf(SettingsManager.rainbowHueDirection) }
    var saturationBoost by remember { mutableFloatStateOf(SettingsManager.rainbowSaturationBoost) }
    var lightnessShift by remember { mutableFloatStateOf(SettingsManager.rainbowLightnessShift) }
    var autoLightness by remember { mutableStateOf(SettingsManager.rainbowAutoLightnessDirection) }
    var visibilityGuard by remember { mutableStateOf(SettingsManager.rainbowVisibilityGuard) }

    val previewColors = remember(
        hueStep, hueDirection, saturationBoost, lightnessShift,
        autoLightness, visibilityGuard, previewBackgroundColor, previewBaseColor
    ) {
        RainbowColorUtils.generatePalette(
            baseColor = previewBaseColor,
            backgroundColor = previewBackgroundColor,
            hueStepDegrees = hueStep,
            hueDirection = hueDirection,
            saturationBoost = saturationBoost,
            lightnessShift = lightnessShift,
            autoLightnessDirection = autoLightness,
            visibilityGuard = visibilityGuard
        )
    }

    Column {
        // 4 色预览
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            previewColors.forEachIndexed { index, color ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(color))
                            .border(
                                1.dp,
                                RustedOnBackground.copy(alpha = 0.15f),
                                RoundedCornerShape(10.dp)
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${index + 1}",
                        fontSize = 11.sp,
                        color = RustedOnBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        SettingSlider(
            title = "色相步长",
            subtitle = "相邻两层在色环上的间隔",
            value = hueStep,
            onValueChange = {
                val rounded = it.roundToInt().toFloat()
                hueStep = rounded
                SettingsManager.rainbowHueStep = rounded
            },
            valueRange = 0f..180f,
            steps = 179,
            valueText = "${hueStep.roundToInt()}°"
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = "色相方向", fontSize = 14.sp, color = RustedOnBackground)
            Text(
                text = "颜色朝哪个方向旋转",
                fontSize = 11.sp,
                color = RustedOnBackground.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("朝背景反色" to 0, "顺时针" to 1, "逆时针" to 2).forEach { (label, value) ->
                    val selected = hueDirection == value
                    TextButton(
                        onClick = {
                            hueDirection = value
                            SettingsManager.rainbowHueDirection = value
                        },
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) RustedPrimary.copy(alpha = 0.15f)
                                else RustedOnBackground.copy(alpha = 0.05f),
                                RoundedCornerShape(8.dp)
                            ),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (selected) RustedPrimary
                            else RustedOnBackground.copy(alpha = 0.7f)
                        )
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        SettingSlider(
            title = "饱和度增强",
            subtitle = "内层相比外层增加/减少的饱和度",
            value = saturationBoost,
            onValueChange = {
                val rounded = (it * 100).roundToInt() / 100f
                saturationBoost = rounded
                SettingsManager.rainbowSaturationBoost = rounded
            },
            valueRange = -0.3f..0.3f,
            steps = 59,
            valueText = "${(saturationBoost * 100).roundToInt()}%"
        )

        SettingSlider(
            title = "亮度偏移",
            subtitle = "内层相比外层变亮/变暗的幅度",
            value = lightnessShift,
            onValueChange = {
                val rounded = (it * 100).roundToInt() / 100f
                lightnessShift = rounded
                SettingsManager.rainbowLightnessShift = rounded
            },
            valueRange = -0.3f..0.3f,
            steps = 59,
            valueText = "${(lightnessShift * 100).roundToInt()}%"
        )

        SettingSwitch(
            title = "背景自适应方向",
            subtitle = "根据背景深浅自动翻转亮度偏移方向",
            checked = autoLightness,
            onCheckedChange = {
                autoLightness = it
                SettingsManager.rainbowAutoLightnessDirection = it
            }
        )

        SettingSwitch(
            title = "可见性保护",
            subtitle = "防止括号颜色融进背景",
            checked = visibilityGuard,
            onCheckedChange = {
                visibilityGuard = it
                SettingsManager.rainbowVisibilityGuard = it
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    hueStep = 60f
                    hueDirection = 0
                    saturationBoost = 0f
                    lightnessShift = 0f
                    autoLightness = true
                    visibilityGuard = true
                    SettingsManager.rainbowHueStep = 60f
                    SettingsManager.rainbowHueDirection = 0
                    SettingsManager.rainbowSaturationBoost = 0f
                    SettingsManager.rainbowLightnessShift = 0f
                    SettingsManager.rainbowAutoLightnessDirection = true
                    SettingsManager.rainbowVisibilityGuard = true
                }
            ) {
                Icon(Icons.Default.FormatColorReset, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("恢复默认", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    subtitle: String? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, color = RustedOnBackground)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.45f))
                }
                Text(
                    text = valueText,
                    fontSize = 11.sp,
                    color = RustedOnBackground.copy(alpha = if (subtitle != null) 0.35f else 0.45f)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, color = RustedOnBackground)
            Text(text = subtitle, fontSize = 11.sp, color = RustedOnBackground.copy(alpha = 0.45f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RustedPrimary,
                checkedTrackColor = RustedPrimary.copy(alpha = 0.3f)
            )
        )
    }
}
