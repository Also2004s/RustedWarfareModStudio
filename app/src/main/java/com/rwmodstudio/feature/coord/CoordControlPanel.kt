package com.rwmodstudio.feature.coord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rwmodstudio.ui.theme.RustedOnBackground
import com.rwmodstudio.ui.theme.RustedPrimary
import com.rwmodstudio.ui.theme.RustedSurface

@Composable
fun CoordControlPanel(
    simTime: Double,
    targets: List<CoordTarget>,
    rndSeed: String,
    selectedMarker: CoordMarker?,
    onSimTimeChange: (Double) -> Unit,
    onTargetChange: (Int, CoordTarget) -> Unit,
    onRndSeedChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RustedSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text("外部面板", fontSize = 12.sp, color = RustedPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Spacer(Modifier.height(8.dp))

        // 模拟时间
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模拟时间", modifier = Modifier.width(70.dp), fontSize = 12.sp, color = RustedOnBackground)
            Slider(
                value = simTime.toFloat(),
                onValueChange = { onSimTimeChange(it.toDouble()) },
                valueRange = 0f..3000f,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = simTime.toInt().toString(),
                onValueChange = { input -> input.toDoubleOrNull()?.let { v -> onSimTimeChange(v.coerceIn(0.0, 3000.0)) } },
                modifier = Modifier.width(60.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(Modifier.height(6.dp))

        // 目标坐标列表
        Text("目标坐标", fontSize = 12.sp, color = RustedOnBackground.copy(alpha = 0.7f))
        Spacer(Modifier.height(4.dp))
        targets.forEachIndexed { index, target ->
            TargetCoordinateRow(
                target = target,
                onChange = { onTargetChange(index, it) }
            )
            if (index < targets.size - 1) {
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(6.dp))

        // 随机种子
        OutlinedTextField(
            value = rndSeed,
            onValueChange = onRndSeedChange,
            label = { Text("随机数种子（可选）", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        selectedMarker?.let { marker ->
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = RustedOnBackground.copy(alpha = 0.1f))
            Spacer(Modifier.height(8.dp))
            Text(
                "选中 Marker #${marker.index}:  offsetX=${formatCoordNumber(marker.offsetX)}, offsetY=${formatCoordNumber(marker.offsetY)}",
                fontSize = 11.sp,
                color = RustedOnBackground.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun TargetCoordinateRow(
    target: CoordTarget,
    onChange: (CoordTarget) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            target.name,
            modifier = Modifier.width(56.dp),
            fontSize = 12.sp,
            color = RustedOnBackground,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        CoordNumberField(
            value = target.x,
            label = "x",
            onChange = { onChange(target.copy(x = it)) },
            modifier = Modifier.weight(1f)
        )
        CoordNumberField(
            value = target.y,
            label = "y",
            onChange = { onChange(target.copy(y = it)) },
            modifier = Modifier.weight(1f)
        )
        CoordNumberField(
            value = target.dir,
            label = "dir",
            onChange = { onChange(target.copy(dir = it)) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CoordNumberField(
    value: Double,
    label: String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.toInt().toString(),
        onValueChange = { input ->
            input.toDoubleOrNull()?.let { onChange(it) }
        },
        label = { Text(label, fontSize = 9.sp) },
        modifier = modifier,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
