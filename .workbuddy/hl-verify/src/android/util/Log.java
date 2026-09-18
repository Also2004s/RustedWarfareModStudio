package android.util;

/** JVM 上的 android.util.Log 替身（离线验证用） */
public final class Log {
    public static boolean QUIET = true;
    public static int d(String tag, String msg) { p("D", tag, msg); return 0; }
    public static int d(String tag, String msg, Throwable t) { p("D", tag, msg); return 0; }
    public static int i(String tag, String msg) { p("I", tag, msg); return 0; }
    public static int w(String tag, String msg) { p("W", tag, msg); return 0; }
    public static int w(String tag, String msg, Throwable t) { p("W", tag, msg); return 0; }
    public static int e(String tag, String msg) { p("E", tag, msg); return 0; }
    public static int e(String tag, String msg, Throwable t) { p("E", tag, msg); return 0; }
    private static void p(String lv, String tag, String msg) {
        if (!QUIET) System.out.println(lv + "/" + tag + ": " + msg);
    }
}
