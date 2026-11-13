package com.rwmodstudio.editor.inline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import com.rwmodstudio.R
import io.github.rosemoe.sora.graphics.InlayHintRenderParams
import io.github.rosemoe.sora.graphics.Paint
import io.github.rosemoe.sora.graphics.inlayHint.InlayHintRenderer
import io.github.rosemoe.sora.lang.styling.inlayHint.CharacterSide
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHint
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** 自定义 hint 的类型名（渲染器按它分发） */
const val INLINE_DECOR_TYPE = "rw_inline_decor"

/**
 * 内联装饰统一配色：取主题主色（#569CD6）。
 * 不用编辑器前景色（深色主题下是白/浅灰、浅色主题下是黑），避免出现黑灰方块。
 */
private val ACCENT: Int = com.rwmodstudio.ui.theme.RustedPrimary.toArgb()

/**
 * 一行内一组同列装饰（sora 对同列的多个 hint 排序不稳定，合并成一个 hint 才能保证顺序）。
 */
class InlineDecorHint(
    line: Int,
    column: Int,
    val decors: List<InlineDecor>
) : InlayHint(line, column, INLINE_DECOR_TYPE, CharacterSide.RIGHT)

/**
 * 内联装饰渲染器：把色块 / 缩略图 / 图标画成**边长等于一个字高**的正方形。
 *
 * 尺寸全部取自 [InlayHintRenderParams]（textTop/textBottom/textHeight），
 * 因此玩家调整字号时每帧自动重新对齐，无需重建 hint。
 */
class InlineDecorRenderer(
    context: Context,
    private val thumbnailProvider: (String) -> Bitmap?,
    private val isPlaying: (String) -> Boolean
) : InlayHintRenderer() {

    private val appContext = context.applicationContext
    /** 着色后的图标位图缓存：key = resId * 31 + color */
    private val iconCache = HashMap<Int, Bitmap>(8)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override val typeName: String
        get() = INLINE_DECOR_TYPE

    override fun onMeasure(inlayHint: InlayHint, paint: Paint, params: InlayHintRenderParams): Float {
        val decors = (inlayHint as? InlineDecorHint)?.decors ?: return 0f
        if (decors.isEmpty()) return 0f
        val size = params.textHeight.toFloat()
        return padLeft(size) + decors.size * size + (decors.size - 1) * gap(size) + padRight(size)
    }

    override fun onRender(
        inlayHint: InlayHint,
        canvas: Canvas,
        paint: Paint,
        params: InlayHintRenderParams,
        colorScheme: EditorColorScheme,
        measuredWidth: Float
    ) {
        val decors = (inlayHint as? InlineDecorHint)?.decors ?: return
        if (decors.isEmpty()) return
        val size = params.textHeight.toFloat()
        if (size <= 0f) return
        // 与文字上下沿严格对齐：正方形边长 = 一个字高
        val top = params.textTop.toFloat()
        val bottom = params.textBottom.toFloat()
        val side = (bottom - top).coerceAtLeast(1f)
        var x = padLeft(size)
        for (decor in decors) {
            when (decor.kind) {
                InlineDecorKind.COLOR -> drawColorSwatch(canvas, x, top, side, decor.argb)
                InlineDecorKind.IMAGE -> drawThumbnail(canvas, x, top, side, decor)
                InlineDecorKind.AUDIO -> {
                    // 音符按钮：播放中在方块下垫一层主色淡底，停止时只画音符，颜色始终统一
                    if (isPlaying(decor.resolvedPath ?: decor.raw)) {
                        paint.style = android.graphics.Paint.Style.FILL
                        paint.color = (ACCENT and 0x00FFFFFF) or 0x33000000
                        canvas.drawRoundRect(
                            RectF(x, top, x + side, top + side),
                            side * 0.22f, side * 0.22f, paint
                        )
                    }
                    drawIcon(canvas, x, top, side, R.drawable.ic_inline_music)
                }

                InlineDecorKind.FILE -> drawIcon(canvas, x, top, side, R.drawable.ic_inline_file)
            }
            x += side + gap(size)
        }
    }

    private fun drawColorSwatch(canvas: Canvas, x: Float, top: Float, side: Float, argb: Int) {
        // 色块比字高方块小一圈（约 72%），并保持直角正方形（不画圆角）
        val inset = side * 0.14f
        val s = side - inset * 2f
        val r = RectF(x + inset, top + inset, x + inset + s, top + inset + s)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = argb
        canvas.drawRect(r, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = (side * 0.05f).coerceAtLeast(1f)
        paint.color = (ACCENT and 0x00FFFFFF) or 0x66000000
        canvas.drawRect(r, paint)
        paint.style = android.graphics.Paint.Style.FILL
    }

    private fun drawIcon(canvas: Canvas, x: Float, top: Float, side: Float, resId: Int) {
        val bmp = tintedIcon(resId, ACCENT)
        if (bmp == null) {
            drawColorSwatch(canvas, x, top, side, ACCENT)
            return
        }
        val dst = RectF(x, top, x + side, top + side)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun drawThumbnail(
        canvas: Canvas,
        x: Float,
        top: Float,
        side: Float,
        decor: InlineDecor
    ) {
        val path = decor.resolvedPath
        val bmp = path?.let(thumbnailProvider)
        if (bmp == null || bmp.width <= 0 || bmp.height <= 0) {
            // 无图（SHARED:/CORE: 内置资源或解码失败）：画图片占位图标
            drawIcon(canvas, x, top, side, R.drawable.ic_inline_image)
            return
        }
        // 整图等比缩放（fitCenter）：只降采样、不裁切，完整图片按比例放进字高方块内居中
        val scale = minOf(side / bmp.width.toFloat(), side / bmp.height.toFloat())
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = x + (side - drawW) / 2f
        val imgTop = top + (side - drawH) / 2f
        val dst = RectF(left, imgTop, left + drawW, imgTop + drawH)
        canvas.drawBitmap(bmp, null, dst, paint)
        // 细边框贴在图片实际区域上，避免浅色贴图糊在浅色背景里
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = (side * 0.05f).coerceAtLeast(1f)
        paint.color = (ACCENT and 0x00FFFFFF) or 0x44000000
        canvas.drawRoundRect(dst, side * 0.08f, side * 0.08f, paint)
        paint.style = android.graphics.Paint.Style.FILL
    }

    /** 把矢量图渲染成固定 64px 的着色位图并缓存（绘制时再缩放到字高） */
    private fun tintedIcon(resId: Int, color: Int): Bitmap? {
        val key = resId * 31 + color
        iconCache[key]?.let { return it }
        val drawable = try {
            ContextCompat.getDrawable(appContext, resId)?.mutate()
        } catch (_: Exception) {
            null
        } ?: return null
        val bmp = Bitmap.createBitmap(ICON_BITMAP_SIZE, ICON_BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        drawable.setBounds(0, 0, ICON_BITMAP_SIZE, ICON_BITMAP_SIZE)
        drawable.draw(c)
        if (iconCache.size > 16) iconCache.clear()
        iconCache[key] = bmp
        return bmp
    }

    private fun gap(size: Float) = (size * 0.18f).coerceAtLeast(1f)
    private fun padLeft(size: Float) = (size * 0.12f).coerceAtLeast(1f)
    private fun padRight(size: Float) = (size * 0.22f).coerceAtLeast(1f)

    private companion object {
        const val ICON_BITMAP_SIZE = 64
    }
}
