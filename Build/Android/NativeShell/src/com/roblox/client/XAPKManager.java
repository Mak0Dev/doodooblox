package com.roblox.client;

import android.content.Context;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/* JADX INFO: loaded from: classes.dex */
public class XAPKManager {
    private static String TAG = "roblox.xapkmanager";
    private static String ASSET_CONTAINER_FILENAME = "main.1.com.doodooblox.blox.obb";
    private static String FINGERPRINT_FILENAME = "fingerprint.txt";
    private static int BUFFER_SIZE = 8192;
    private static final Lock mLock = new ReentrantLock();
    private static String mCachedAssetPath = null;

    public static void unpackAssetsAsync(final Context context) {
        Runnable runnable = new Runnable() { // from class: com.roblox.client.XAPKManager.1
            @Override // java.lang.Runnable
            public void run() {
                XAPKManager.unpackAssets(context);
            }
        };
        new Thread(runnable).start();
    }

    public static String unpackAssets(Context context) {
        mLock.lock();
        try {
            if (mCachedAssetPath == null) {
                File baseAssetPath = context.getDir("assets", 0);
                String obbFingerprint = readOBBFingerprint(context);
                String assetsFingerprint = readAssetsFingerprint(baseAssetPath.toString());
                if (assetsFingerprint.isEmpty() || !obbFingerprint.equals(assetsFingerprint)) {
                    if (!obbFingerprint.isEmpty()) {
                        baseAssetPath.delete();
                        baseAssetPath.mkdirs();
                        unzipContainer(context, baseAssetPath.toString());
                        writeFingerprint(obbFingerprint, baseAssetPath.toString());
                    } else if (assetsFingerprint.isEmpty()) {
                        Log.e(TAG, "Unable to reach a valid OBB file. Assets are not available");
                    }
                } else {
                    Log.v(TAG, "Assets are up to date");
                }
                mCachedAssetPath = baseAssetPath + "/content";
            }
            return mCachedAssetPath;
        } finally {
            mLock.unlock();
        }
    }

    private static String readOBBFingerprint(Context context) {
        String fingerprint = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(context.getAssets().open(FINGERPRINT_FILENAME)));
            fingerprint = reader.readLine();
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                }
            }
        }
        return fingerprint != null ? fingerprint : "";
    }

    private static String readAssetsFingerprint(String baseAssetPath) {
        String fingerprint = "";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(new File(baseAssetPath, FINGERPRINT_FILENAME)));
            fingerprint = reader.readLine();
        } catch (IOException e) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                }
            }
        }
        return fingerprint != null ? fingerprint : "";
    }

    private static void unzipContainer(Context context, String baseAssetPath) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream inputStream = new BufferedInputStream(context.getAssets().open(ASSET_CONTAINER_FILENAME));
            ZipInputStream zin = new ZipInputStream(inputStream);
            while (true) {
                try {
                    ZipEntry ze = zin.getNextEntry();
                    if (ze != null) {
                        String path = baseAssetPath + "/" + ze.getName();
                        File unzipFile = new File(path);
                        if (ze.isDirectory()) {
                            if (!unzipFile.isDirectory()) {
                                unzipFile.mkdirs();
                            }
                        } else {
                            File parentDir = unzipFile.getParentFile();
                            if (parentDir != null && !parentDir.isDirectory()) {
                                parentDir.mkdirs();
                            }
                            FileOutputStream out = new FileOutputStream(unzipFile, false);
                            BufferedOutputStream fout = new BufferedOutputStream(out, BUFFER_SIZE);
                            while (true) {
                                try {
                                    int size = zin.read(buffer, 0, BUFFER_SIZE);
                                    if (size == -1) {
                                        break;
                                    } else {
                                        fout.write(buffer, 0, size);
                                    }
                                } catch (Throwable th) {
                                    fout.flush();
                                    fout.close();
                                    throw th;
                                }
                            }
                            zin.closeEntry();
                            fout.flush();
                            fout.close();
                        }
                    } else {
                        zin.close();
                        return;
                    }
                } catch (Throwable th2) {
                    zin.close();
                    throw th2;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unzip exception", e);
        }
    }

    private static void writeFingerprint(String fingerprint, String baseAssetPath) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(new File(baseAssetPath + "/" + FINGERPRINT_FILENAME));
            out.write(fingerprint.getBytes());
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {
                }
            }
        }
    }
}
