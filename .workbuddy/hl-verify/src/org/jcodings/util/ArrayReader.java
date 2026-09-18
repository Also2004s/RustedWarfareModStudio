package org.jcodings.util;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** JVM 版 ArrayReader：从 assets/tables 目录读 .bin 表，供本地正则实验用 */
public class ArrayReader {

    public static String TABLE_DIR = System.getProperty("tables", "tables");

    public static DataInputStream openStream(String name) {
        String path = TABLE_DIR + "/" + name + ".bin";
        InputStream is = null;
        try {
            is = new FileInputStream(path);
        } catch (IOException e) {
            is = ArrayReader.class.getResourceAsStream("/tables/" + name + ".bin");
        }
        if (is == null) throw new RuntimeException("entry: " + path + " not found");
        return new DataInputStream(new BufferedInputStream(is));
    }

    public static byte[] readByteArray(String name) {
        DataInputStream dis = openStream(name);
        try {
            int size = dis.readInt();
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = dis.readByte();
            return bytes;
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            try { dis.close(); } catch (IOException ex) { }
        }
    }

    public static int[] readIntArray(String name) {
        DataInputStream dis = openStream(name);
        try {
            int size = dis.readInt();
            int[] ints = new int[size];
            for (int i = 0; i < size; i++) ints[i] = dis.readInt();
            return ints;
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            try { dis.close(); } catch (IOException ex) { }
        }
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
                for (int k = 0; k < iSize; k++) iints[k] = dis.readInt();
            }
            return ints;
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            try { dis.close(); } catch (IOException ex) { }
        }
    }
}
