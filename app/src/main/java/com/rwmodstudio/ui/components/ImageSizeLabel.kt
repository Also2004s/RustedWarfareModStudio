package com.rwmodstudio.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.rwmodstudio.core.ImageSize
import com.rwmodstudio.core.ImageSizeCache
import com.rwmodstudio.ui.theme.RustedOnBackground
import java.io.File

/**
 * 在 IO 线程读取图片真实像素尺寸（只读文件头，不整图解码）。
 *
 * [refreshKey] 变化时重新读取；文件被替换（mtime/大小变化）时由缓存自动失效。
 * 尺寸未知或解码失败返回 null。
 */
@Composable
fun rememberImageSize(file: File?, refreshKey: Any? = null): ImageSize? {
    var size by remember(file?.absolutePath, refreshKey) { mutableStateOf<ImageSize?>(null) }
    LaunchedEffect(file?.absolutePath, refreshKey) {
        size = file?.let { ImageSizeCache.load(it) }
    }
    return size
}

/** 统一的图片尺寸文字（宽×高）；尺寸未知时不显示，避免出现错误数字。 */
@Composable
fun ImageSizeLabel(
    size: ImageSize?,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.sp,
    color: Color = RustedOnBackground.copy(alpha = 0.4f)
) {
    if (size != null) {
        Text(
            text = size.label,
            modifier = modifier,
            fontSize = fontSize,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
