package com.rwmodstudio.editor

import android.graphics.Bitmap
import android.graphics.Paint
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rwmodstudio.util.IniImageReader
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.File
import java.util.concurrent.Executors

/**
 * 补全弹窗条目适配器：路径类 label 改为「中间省略」，尾部永远可见；
 * 图片路径补全项在左侧图标位直接显示缩略图（按需异步解码，LRU 缓存）。
 *
 * sora 默认布局（default_completion_result_item.xml）里 label 是 wrap_content + marquee，
 * 弹窗宽度只有 300dp（平板跟随光标）或屏幕 7/8（手机），长路径（如
 * ROOT:/units/xxx/yyy.png）会把整行撑满、从右侧被裁掉，文件名看不到；
 * marquee 又只在选中项才滚动，未选中项就是死截断。
 * 这里把 label 改成 weight=1 + MIDDLE 省略，头部（ROOT:/ 等）和尾部（文件名）都保留；
 * 说明位限宽，不再和路径抢空间。
 *
 * @param resolveImagePath 把补全项解析成「已存在的图片绝对路径」，非图片/找不到返回 null；
 *   传 null 时退回显示 item.icon（原有行为）。
 */
class PathAwareCompletionAdapter(
    private val resolveImagePath: ((CompletionItem) -> String?)? = null
) : EditorCompletionAdapter() {
    private class Holder(val icon: ImageView, val label: TextView, val desc: TextView)

    override fun getItemHeight(): Int = dp(45)

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup?,
        isCurrentCursorPosition: Boolean
    ): View {
        val root = convertView as? LinearLayout ?: buildItemView()
        val holder = root.tag as Holder
        val item: CompletionItem = getItem(position)

        // 图标位：图片路径显示缩略图，其余保持原图标
        val thumbPath = resolveImagePath?.invoke(item)
        holder.icon.tag = thumbPath
        if (thumbPath != null) {
            val cached = thumbCache.get(thumbPath)
            if (cached != null) {
                holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.icon.setImageBitmap(cached)
            } else {
                holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.icon.setImageDrawable(item.icon)
                loadThumbnail(thumbPath, holder.icon)
            }
        } else {
            holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.icon.setImageDrawable(item.icon)
        }

        holder.label.text = item.label
        holder.label.setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY))

        holder.desc.text = item.desc
        holder.desc.setTextColor(getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY))
        // 说明为空时不占位，把宽度全留给路径；显示缩略图的图片项也不再显示右侧注释
        holder.desc.visibility =
            if (thumbPath != null || item.desc.isNullOrEmpty()) View.GONE else View.VISIBLE

        val strike = if (item.deprecated) Paint.STRIKE_THRU_TEXT_FLAG else 0
        holder.label.paintFlags =
            (holder.label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()) or strike
        holder.desc.paintFlags =
            (holder.desc.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()) or strike

        root.setBackgroundColor(
            if (isCurrentCursorPosition) {
                getThemeColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT)
            } else {
                0
            }
        )
        return root
    }

    /** 后台解码缩略图；回主线程前校验图标位是否已被复用到别的条目 */
    private fun loadThumbnail(path: String, imageView: ImageView) {
        thumbExecutor.execute {
            val bmp = thumbCache.get(path)
                ?: runCatching { IniImageReader.decodeSampled(File(path), THUMB_MAX_DIM) }
                    .getOrNull()
                    ?.also { thumbCache.put(path, it) }
            if (bmp == null) return@execute
            imageView.post {
                if (imageView.tag == path) {
                    imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    imageView.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun buildItemView(): LinearLayout {
        val root = LinearLayout(getContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val icon = ImageView(getContext()).apply {
            // 图标列固定 20dp：与改造前一致，缩略图与普通图标共用同一列
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(getContext()).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val desc = TextView(getContext()).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // 说明位最多占 104dp，保证 label 至少有可读的尾部空间
            maxWidth = dp(104)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        root.addView(icon)
        root.addView(label)
        root.addView(desc)
        root.tag = Holder(icon, label, desc)
        return root
    }

    private fun dp(value: Int): Int =
        (value * getContext().resources.displayMetrics.density).toInt()

    private companion object {
        const val THUMB_MAX_DIM = 48
        val thumbCache = LruCache<String, Bitmap>(48)
        val thumbExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "completion-thumb").apply { isDaemon = true }
        }
    }
}

private val COMPLETION_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "webp", "gif")

/**
 * 补全项插入文本 → 可显示缩略图的图片绝对路径。
 * 非图片、SHARED:/CORE: 内置资源、文件不存在都返回 null（显示原图标）。
 */
internal fun resolveCompletionImagePath(raw: String, projectRoot: String, fileDir: String): String? {
    val v = raw.trim()
        .trim('"', '\'', '[', ']', '(', ')')
        .substringBefore(',')
        .trim()
    if (v.isEmpty()) return null
    val lower = v.lowercase()
    if (lower.startsWith("shared:") || lower.startsWith("core:")) return null
    if (lower.substringAfterLast('.', "") !in COMPLETION_IMAGE_EXTS) return null
    val candidates = when {
        lower.startsWith("root:") ->
            listOf(File(projectRoot, v.substringAfter(':').trimStart('/')))

        v.startsWith("/") -> listOf(File(v))
        else -> listOfNotNull(
            fileDir.takeIf { it.isNotBlank() }?.let { File(it, v) },
            projectRoot.takeIf { it.isNotBlank() }?.let { File(it, v) }
        )
    }
    return candidates.firstOrNull { it.isFile }?.absolutePath
}
