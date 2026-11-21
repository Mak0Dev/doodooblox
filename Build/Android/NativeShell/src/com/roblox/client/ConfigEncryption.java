package com.roblox.client;

import android.util.Base64;
import java.io.IOException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

/* JADX INFO: loaded from: classes.dex */
public class ConfigEncryption {
    private static long x = -708949159;
    private static long y = -1078355730;
    private static long z = 1115474503;
    private static final char[] pw = xorspw();
    private static final byte[] sal = xorssal();

    private static long xors() {
        x ^= x << 16;
        x ^= x >> 5;
        x ^= x << 1;
        long t = x;
        x = y;
        y = z;
        z = (x ^ t) ^ y;
        return z;
    }

    private static byte[] xorssal() {
        int t = 13;
        while (true) {
            t--;
            if (t <= 0) {
                break;
            }
            xors();
        }
        byte[] s = new byte[8];
        for (int i = 0; i < 8; i++) {
            s[i] = (byte) xors();
        }
        return s;
    }

    private static char[] xorspw() {
        String s = base64Encode(xorssal());
        return s.toCharArray();
    }

    private static byte[] base64Decode(String property) throws IOException {
        return Base64.decode(property, 3);
    }

    private static String base64Encode(byte[] bytes) {
        return Base64.encodeToString(bytes, 3);
    }

    public static String encrypt(String property) {
        try {
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBEWithMD5AndDES");
            SecretKey key = keyFactory.generateSecret(new PBEKeySpec(pw));
            Cipher pbeCipher = Cipher.getInstance("PBEWithMD5AndDES");
            pbeCipher.init(1, key, new PBEParameterSpec(sal, 20));
            String result = base64Encode(pbeCipher.doFinal(property.getBytes("UTF-8")));
            return result;
        } catch (Exception e) {
            return property;
        }
    }

    public static String decrypt(String property) {
        try {
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBEWithMD5AndDES");
            SecretKey key = keyFactory.generateSecret(new PBEKeySpec(pw));
            Cipher pbeCipher = Cipher.getInstance("PBEWithMD5AndDES");
            pbeCipher.init(2, key, new PBEParameterSpec(sal, 20));
            String result = new String(pbeCipher.doFinal(base64Decode(property)), "UTF-8");
            return result;
        } catch (Exception e) {
            return property;
        }
    }
}
