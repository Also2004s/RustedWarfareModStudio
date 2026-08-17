package org.jcodings.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Shadow of jcodings ArrayReader that loads .bin tables from Android assets.
 *
 * The original class uses Class.getResourceAsStream("/tables/..."), which does not work
 * reliably on Android when the tables are packaged inside the APK. This shadow is placed
 * in the same package so it takes precedence over the dependency class, and it falls back
 * to the original loading strategy if the application context has not been initialized.
 */
public class ArrayReader {

    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static DataInputStream openStream(String name) {
        String entry = "/tables/" + name + ".bin";
        InputStream is = null;

        // Primary: load from Android assets, where the tables are stored uncompressed.
        Context ctx = appContext;
        if (ctx != null) {
            try {
                is = ctx.getAssets().open("tables/" + name + ".bin");
                Log.d("JcodingsArrayReader", "Loaded table from assets: " + name);
            } catch (IOException e) {
                Log.w("JcodingsArrayReader", "Failed to load table from assets: " + name, e);
            }
        }

        // Fallback: original classpath loading.
        if (is == null) {
            is = ArrayReader.class.getResourceAsStream(entry);
        }

        if (is == null) {
            throw new RuntimeException("entry: " + entry + " not found");
        }
        return new DataInputStream(new BufferedInputStream(is));
    }

    public static byte[] readByteArray(String name) {
        DataInputStream dis = openStream(name);
        try {
            int size = dis.readInt();
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) {
                bytes[i] = dis.readByte();
            }
            checkAvailable(dis, name);
            return bytes;
        } catch (IOException ioe) {
            decorate(ioe, name);
        } finally {
            try {
                dis.close();
            } catch (IOException ex) {
                // ignore
            }
        }
        return null;
    }

    public static int[] readIntArray(String name) {
        DataInputStream dis = openStream(name);
        try {
            int size = dis.readInt();
            int[] ints = new int[size];
            for (int i = 0; i < size; i++) {
                ints[i] = dis.readInt();
            }
            checkAvailable(dis, name);
            return ints;
        } catch (IOException ioe) {
            decorate(ioe, name);
        } finally {
            try {
                dis.close();
            } catch (IOException ex) {
                // ignore
            }
        }
        return null;
    }

    public static int[][] readNestedIntArray(String name) {
        DataInputStream dis = openStream(name);
        try {
            int size = dis.readInt();
            int[][] ints = new int[size][];
            for (int i = 0; i < size; i++) {
                int iSize = dis.readInt();
                int[] iints = new int[iSize];
                ints[i] = iints;
                for (int k = 0; k < iSize; k++) {
                    iints[k] = dis.readInt();
                }
            }
            checkAvailable(dis, name);
            return ints;
        } catch (IOException ioe) {
            decorate(ioe, name);
        } finally {
            try {
                dis.close();
            } catch (IOException ex) {
                // ignore
            }
        }
        return null;
    }

    static void checkAvailable(DataInputStream dis, String name) throws IOException {
        if (dis.available() != 0) throw new RuntimeException("length mismatch for table: " + name + " (" + dis.available() + " left)");
    }

    static void decorate(IOException ioe, String name) {
        throw new RuntimeException("problem reading table: " + name + ": " + ioe);
    }
}
