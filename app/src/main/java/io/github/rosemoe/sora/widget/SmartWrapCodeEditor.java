package io.github.rosemoe.sora.widget;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import io.github.rosemoe.sora.widget.layout.Layout;
import io.github.rosemoe.sora.widget.layout.SmartWordwrapLayout;
import io.github.rosemoe.sora.widget.layout.WordwrapLayout;

/**
 * 支持「智能换行（逻辑断点）」的 CodeEditor。
 *
 * <p>本类声明在库包 io.github.rosemoe.sora.widget 内，以便直接访问 CodeEditor 的包私有
 * {@code layout} 字段（sora-editor 0.24.6 没有公开 setter）。升级 sora-editor 时若该字段或
 * 相关 API 变化，编译期即会报错，便于同步移植。
 *
 * <p>关闭智能换行（或未开启自动换行）时，行为与原生 CodeEditor 完全一致。
 */
public class SmartWrapCodeEditor extends CodeEditor {

    private static final String TAG = "SmartWrapCodeEditor";

    private boolean smartWrapEnabled;

    public SmartWrapCodeEditor(@NonNull Context context) {
        this(context, true);
    }

    public SmartWrapCodeEditor(@NonNull Context context, boolean smartWrapEnabled) {
        super(context);
        this.smartWrapEnabled = smartWrapEnabled;
    }

    /**
     * 供外部切换智能换行；切换后立即重建布局。
     */
    public void applySmartWrap(boolean enabled) {
        if (smartWrapEnabled == enabled) {
            return;
        }
        smartWrapEnabled = enabled;
        if (isWordwrap()) {
            createLayout(true);
        }
    }

    @Override
    protected void createLayout(boolean clearWordwrapCache) {
        super.createLayout(clearWordwrapCache);
        if (!smartWrapEnabled || !isWordwrap()) {
            return;
        }
        Layout current = getLayout();
        if (!(current instanceof WordwrapLayout)) {
            return;
        }
        // oldLayout 参数传入 null：SmartWordwrapLayout 不复用基类的私有 rowTable，统一重建。
        SmartWordwrapLayout smart = new SmartWordwrapLayout(
                this, getText(), isAntiWordBreaking(), isWordwrapRtlDisplaySupport(), null, clearWordwrapCache);
        current.destroyLayout();
        layout = smart;
        Log.d(TAG, "smart wrap layout installed (clearCache=" + clearWordwrapCache + ")");
    }
}