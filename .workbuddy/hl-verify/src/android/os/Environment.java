package android.os;

import java.io.File;

/** JVM 上的 android.os.Environment 替身 */
public class Environment {
    public static File getExternalStorageDirectory() {
        File f = new File(System.getProperty("java.io.tmpdir"), "hlverify-sdcard");
        f.mkdirs();
        return f;
    }
}
