package androidx.compose.runtime;

/** JVM 上的 androidx.compose.runtime 替身（只实现 HighlightRuleManager 静态初始化所需的那一个方法） */
public final class SnapshotStateKt {

    public static <T> MutableState<T> mutableStateOf$default(
            T value, SnapshotMutationPolicy<T> policy, int mask, Object holder) {
        return new Impl<>(value);
    }

    static final class Impl<T> implements MutableState<T> {
        private T v;
        Impl(T v) { this.v = v; }
        public T getValue() { return v; }
        public void setValue(T value) { this.v = value; }
    }
}
