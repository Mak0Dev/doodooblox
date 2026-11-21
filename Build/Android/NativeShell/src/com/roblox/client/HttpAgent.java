package com.roblox.client;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.net.CookieHandler;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;

/* JADX INFO: loaded from: classes.dex */
public class HttpAgent {
    private static final String TAG = "roblox.httpagent";

    public static class HttpHeader {
        public String header;
        public String value;
    }

    public static void onCreate(Activity activity) {
        CookieSyncManager.createInstance(activity);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieSyncManager.getInstance().startSync();
        WebkitCookieManagerProxy coreCookieManager = new WebkitCookieManagerProxy(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(coreCookieManager);
    }

    public static void onPause(File cacheDir, String url) {
        CookieSyncManager.getInstance().stopSync();
        flushToDisk(cacheDir, url);
    }

    public static void onResume() {
        CookieSyncManager.getInstance().startSync();
    }

    public static void flushToDisk(File cacheDir, String url) {
        if (url != null && !url.isEmpty()) {
            Uri uri = Uri.parse(url);
            if (uri.getHost() != null) {
                if (uri.getHost().contains(".robloxlabs.com") || uri.getHost().contains(".karblox.cc")
                        || uri.getHost().endsWith("gametest1.doodooblox.wtf")) {
                    // DoodooBlox: https lookup also returns Secure cookies (the engine sends them over http)
                    RobloxSettings.saveRobloxCookies(uri.getHost(), CookieManager.getInstance().getCookie("https://" + uri.getHost()));
                }
            }
        }
    }

    public static HttpURLConnection urlToConnection(String url, String post, HttpHeader[] headerList) throws IOException {
        URL urlObj = new URL(url);
        HttpURLConnection httpConnection = (HttpURLConnection) urlObj.openConnection();
        if (headerList != null && Array.getLength(headerList) > 0) {
            for (HttpHeader header : headerList) {
                httpConnection.setRequestProperty(header.header, header.value);
            }
        }
        httpConnection.setRequestProperty("User-Agent", RobloxSettings.userAgent());
        httpConnection.setConnectTimeout(60000);
        httpConnection.setReadTimeout(60000);
        if (post != null) {
            httpConnection.setDoOutput(true);
            httpConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            OutputStreamWriter out = new OutputStreamWriter(httpConnection.getOutputStream());
            out.write(post);
            out.close();
        }
        int status = httpConnection.getResponseCode();
        if (status != 200) {
            Log.i(TAG, "User-Agent:" + httpConnection.getRequestProperty("User-Agent"));
            Log.i(TAG, "URL:" + url);
            Log.i(TAG, "HTTP Status:" + status);
        }
        return httpConnection;
    }

    public static byte[] readUrlToBytes(String url, String post, HttpHeader[] headerList) {
        HttpURLConnection httpConnection = null;
        byte[] result = null;
        try {
            httpConnection = urlToConnection(url, post, headerList);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (true) {
                int length = httpConnection.getInputStream().read(buffer);
                if (length == -1) {
                    break;
                }
                baos.write(buffer, 0, length);
            }
            result = baos.toByteArray();
        } catch (IOException e) {
            Log.i(TAG, "readUrlToBytes:" + e.toString());
            Log.i(TAG, "url:" + url);
            if (post != null) {
                // DoodooBlox: never log the POST body (it carries the login password)
            }
        } finally {
            if (httpConnection != null) {
                httpConnection.disconnect();
            }
        }
        return result;
    }

    public static String readUrlToString(String url, String post, HttpHeader[] headerList) {
        byte[] b = readUrlToBytes(url, post, headerList);
        if (b == null) {
            return null;
        }
        try {
            return new String(b, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.i(TAG, "readUrlToString:" + e.toString());
            return null;
        }
    }
}
