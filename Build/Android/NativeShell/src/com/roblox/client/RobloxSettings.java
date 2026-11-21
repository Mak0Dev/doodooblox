package com.roblox.client;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Build;
import android.webkit.CookieManager;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class RobloxSettings {
    private static final String TAG = "roblox.config";
    private static Context mContext = null;
    private static String mVersion = "version missing";
    private static boolean mFakeUserAgent = true;
    private static String mFakeUserAgentString = null;
    private static String mActualUserAgentString = null;
    private static String mUserAgentSuffix = null;
    private static boolean mIsPhone = true;
    private static String mBaseUrl = null;
    private static String mBaseMobileUrl = null;
    private static boolean mEnableBreakpad = false;
    private static boolean mCleanupBreakpadDumps = true;
    private static String mWebViewURLOverride = null;
    private static boolean mUseWebURLOverride = false;
    private static String mCacheDirectory = null;
    private static String mFilesDirectory = null;
    private static int mNDKProfilerFrequency = 0;
    private static String mDeviceNotSupported = null;
    private static boolean mDeviceNotSupportedSkippable = true;
    private static File mRobloxCookiesTmpFile = null;
    private static boolean mIsInternalBuild = false;
    static SharedPreferences mKeyValues = null;

    // DoodooBlox: auth goes over https so the site's Secure cookies are accepted by the
    // WebView cookie store (Android rejects Secure cookies set over http). Android 4.x
    // cannot do Cloudflare's TLS, so it stays on http there.
    static String secureScheme() {
        return Build.VERSION.SDK_INT >= 21 ? "https://" : "http://";
    }

    static String authCookies() {
        CookieManager cm = CookieManager.getInstance();
        String c = cm.getCookie("https://gametest1.doodooblox.wtf");
        return c != null ? c : cm.getCookie("http://gametest1.doodooblox.wtf");
    }

    // The session is DooDooBlox's .ROBLOSECURITY (set without Secure, so the engine can send it over
    // http): the site proxy turns it into the account for PlaceLauncher and for the site's pages.
    static boolean hasSession() {
        String c = authCookies();
        return c != null && c.contains(".ROBLOSECURITY=");
    }

    static String loginUrl() {
        return secureScheme() + "gametest1.doodooblox.wtf/mobileapi/login";
    }

    static String logoutUrl() {
        return secureScheme() + "gametest1.doodooblox.wtf/mobileapi/logout";
    }

    private static native boolean nativeEnableNDKProfiler(int i);

    private static native void nativeInitBreakpad(boolean z);

    private static native void nativeInitFastLog();

    private static native void nativeLocaleDecimalPoint(Byte b);

    private static native void nativeSetBaseUrl(String str);

    private static native void nativeSetCacheDirectory(String str);

    private static native void nativeSetCookiesForDomain(String str, String str2);

    private static native void nativeSetExceptionReasonFilename(String str);

    private static native void nativeSetFilesDirectory(String str);

    private static native void nativeSetHttpProxy(String str, long j);

    private static native void nativeSetPlatformUserAgent(String str);

    private static native void nativeSetRobloxVersion(String str);

    static String version() {
        return mVersion;
    }

    static boolean isInternalBuild() {
        return mIsInternalBuild;
    }

    static boolean isTablet() {
        return !mIsPhone;
    }

    static boolean isPhone() {
        return mIsPhone;
    }

    static String baseUrl() {
        return "http://" + (isTablet() ? mBaseUrl : mBaseMobileUrl);
    }

    static String baseUrlSecure() {
        return secureScheme() + (isTablet() ? mBaseUrl : mBaseMobileUrl);
    }

    static String baseUrlSecureWWW() {
        return secureScheme() + mBaseUrl;
    }

    static boolean isTestSite() {
        String bUrl = isTablet() ? mBaseUrl : mBaseMobileUrl;
        if (bUrl.indexOf(".robloxlabs.com") != -1) {
            return true;
        }
        return false;
    }

    static String baseURLNoHttp() {
        return mBaseUrl;
    }

    static void setBaseUrlDebug(String newUrl) {
        mBaseUrl = newUrl;
        updateSharedPrefs("BaseUrl", newUrl);
    }

    static void setBaseMobileUrlDebug(String newUrl) {
        mBaseMobileUrl = newUrl;
        updateSharedPrefs("BaseUrl", newUrl);
    }

    static boolean enableBreakpad() {
        return mEnableBreakpad;
    }

    static boolean cleanupBreakpadDumps() {
        return mCleanupBreakpadDumps;
    }

    static String webViewURLOverride() {
        return mWebViewURLOverride;
    }

    static boolean useWebURLOverride() {
        return mUseWebURLOverride;
    }

    static String userAgentSuffix() {
        return mUserAgentSuffix;
    }

    static void updateSharedPrefs(String key, String value) {
        try {
            SharedPreferences.Editor editor = mKeyValues.edit();
            editor.putString("internalDebugUrl", value);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Error setting SharedPrefs: " + e.toString());
        }
    }

    static String userAgentNotFaked() {
        if (mActualUserAgentString != null) {
            return mActualUserAgentString;
        }
        long memory = Utils.getDeviceTotalMegabytes();
        Point screenSize = Utils.getScreenSize(mContext);
        Point dpi = Utils.getScreenDpi(mContext);
        Point dpsz = Utils.getScreenDpSize(mContext);
        String deviceName = Utils.getDeviceName();
        String androidVersion = Build.VERSION.RELEASE;
        String webKitVersion = Utils.getWebkitVersion(mContext);
        mActualUserAgentString = Utils.format("Mozilla/5.0 (%dMB; %dx%d; %dx%d; %dx%d; %s; %s) %s (KHTML, like Gecko)  ROBLOX Android App %s", Long.valueOf(memory), Integer.valueOf(screenSize.x), Integer.valueOf(screenSize.y), Integer.valueOf(dpi.x), Integer.valueOf(dpi.y), Integer.valueOf(dpsz.x), Integer.valueOf(dpsz.y), deviceName, androidVersion, webKitVersion, mVersion);
        return mActualUserAgentString;
    }

    static String userAgent() {
        return mFakeUserAgent ? mFakeUserAgentString : userAgentNotFaked();
    }

    static String searchGamesUrl(String term) {
        return Utils.format("%sBrowse.aspx?name=%s", baseUrl(), term);
    }

    static String userInfoUrl() {
        return Utils.format("%smobileapi/userinfo", baseUrlSecure());
    }

    static String signUpUrl() {
        return baseUrlSecureWWW() + "mobileapi/securesignup";
    }

    static String signUpUrlArgs(String userName, String password, String gender, String dateOfBirth) {
        return Utils.format("userName=%s&password=%s&gender=%s&dateOfBirth=%s", userName, password, gender, dateOfBirth);
    }

    static String usernameCheckUrl(String userName) {
        return Utils.format("%sUserCheck/checkifinvalidusernameforsignup?username=%s", baseUrlSecureWWW(), userName);
    }

    static String recommendUsernameUrl(String userName) {
        return Utils.format("%sUserCheck/getrecommendedusername?usernameToTry=%s", baseUrlSecureWWW(), userName);
    }

    static String passwordCheckUrl() {
        return baseUrlSecureWWW() + "UserCheck/validatepasswordforsignup";
    }

    static String passwordCheckUrlArgs(String userName, String password) {
        return Utils.format("password=%s&username=%s", password, userName);
    }

    // DoodooBlox: the 2014 phone build pointed at m.roblox.com-style paths (games/list,
    // inventory, inbox, my-groups) that the DoodooBlox site does not have - use the
    // site's real pages for phones and tablets alike.
    static String gamesUrl() {
        return baseUrl() + "games";
    }

    static String catalogUrl() {
        return baseUrl() + "catalog/";
    }

    static String inventoryUrl() {
        return baseUrl() + "My/Character.aspx";
    }

    static String buildersClubUrl() {
        return baseUrl() + "mobile-app-upgrades/";
    }

    static String profileUrl() {
        return baseUrl() + "User.aspx";
    }

    static String messagesUrl() {
        return baseUrl() + "My/Messages.aspx";
    }

    static String groupsUrl() {
        return baseUrl() + "My/Groups.aspx";
    }

    static String forumsUrl() {
        return baseUrl() + "Forum/Default.aspx";
    }

    static String leaderboardsUrl() {
        return baseUrl() + "leaderboards";
    }

    static String upgradeCheckUrl() {
        return baseUrl() + "mobileapi/check-app-version?appVersion=AppAndroidV" + version();
    }

    static String blacklistUrl() {
        return "https://clientsettings.api." + baseURLNoHttp().replace("www.", "") + "Setting/QuietGet/AndroidAppSettings/?apiKey=76E5A40C-3AE1-4028-9F10-7C62520BD94F";
    }

    static String verifyPurchaseReceiptUrlForGoogle() {
        return baseUrlSecure() + "mobileapi/google-purchase?";
    }

    static String verifyPurchaseReceiptUrlForAmazon() {
        return baseUrlSecure() + "mobileapi/amazon-purchase?";
    }

    static void saveRobloxCookies(String domain, String cookies) {
        if (domain != null && cookies != null) {
            Utils.writeToFile(mRobloxCookiesTmpFile, domain + "\n" + cookies);
        }
    }

    static String exceptionReasonFilename() {
        return "exception_reason.txt";
    }

    static String deviceNotSupportedString() {
        return mDeviceNotSupported;
    }

    static boolean deviceNotSupportedSkippable() {
        return mDeviceNotSupportedSkippable;
    }

    static void initConfig(Context c) throws IOException {
        mContext = c;
        mKeyValues = mContext.getSharedPreferences("prefs", 4);
        try {
            JSONObject j = Utils.loadJson(c, R.raw.roblox_settings);
            mFakeUserAgent = j.getBoolean("FakeUserAgent");
            mFakeUserAgentString = j.getString("FakeUserAgentString");
            mUserAgentSuffix = j.getString("UserAgentSuffix");
            mBaseUrl = j.getString("BaseUrl");
            mBaseMobileUrl = j.getString("BaseMobileUrl");
            mEnableBreakpad = j.getBoolean("EnableBreakpad");
            mCleanupBreakpadDumps = j.optBoolean("CleanupBreakpadDumps", true);
            mWebViewURLOverride = j.optString("WebViewURLOverride", null);
            mUseWebURLOverride = j.optBoolean("UseURLOverride", false);
            mNDKProfilerFrequency = j.getInt("NDKProfilerFrequency");
            mIsInternalBuild = j.optBoolean("InternalBuild", false);
            if (mIsInternalBuild) {
                mBaseUrl = mKeyValues.getString("internalDebugUrl", baseUrl());
            }
            try {
                PackageInfo pinfo = ((Application) mContext).getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
                mVersion = pinfo.versionName;
                if (!Utils.getDeviceHasNEON()) {
                    mDeviceNotSupported = "Requires NEON instructions";
                    mDeviceNotSupportedSkippable = false;
                } else if (Utils.getScreenDpi(mContext).x < 180) {
                    mDeviceNotSupported = "";
                } else if (Build.MODEL.equals("SM\u00ad-T210R")) {
                    mDeviceNotSupported = "SM\u00ad-T210R";
                }
                Point dpsz = Utils.getScreenDpSize(mContext);
                mIsPhone = dpsz.x < 800 || dpsz.y < 500;
                mCacheDirectory = mContext.getCacheDir().getAbsolutePath();
                mFilesDirectory = mContext.getFilesDir().getAbsolutePath();
                mRobloxCookiesTmpFile = new File(mCacheDirectory, "2345sd-2345234-cookies.txt");
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException("Cannot Read Package Info for Version String: " + e.getMessage());
            }
        } catch (JSONException e2) {
            throw new IOException("Cannot parse JSON resource: res/raw/roblox_settings: " + e2.getMessage());
        }
    }

    public static void enableNDKProfiler(boolean enable) {
        if (enable) {
            boolean doesExist = nativeEnableNDKProfiler(mNDKProfilerFrequency);
            if (doesExist) {
                Log.e(TAG, "Setting NDK Profiler frequency: " + mNDKProfilerFrequency);
                return;
            }
            return;
        }
        boolean doesExist2 = nativeEnableNDKProfiler(0);
        if (doesExist2) {
            Utils.chmod(mFilesDirectory + "/gmon.out", 493);
        }
    }

    public static void updateNativeSettings() {
        nativeSetExceptionReasonFilename(exceptionReasonFilename());
        if (baseUrl() == null) {
            throw new NullPointerException("Missing baseUrl()");
        }
        if (mIsInternalBuild) {
            nativeSetBaseUrl(mKeyValues.getString("internalDebugUrl", baseUrl()));
        } else {
            nativeSetBaseUrl(baseUrl());
        }
        if (mCacheDirectory == null) {
            throw new NullPointerException("Missing cacheDirectory");
        }
        nativeSetCacheDirectory(mCacheDirectory);
        nativeSetFilesDirectory(mFilesDirectory);
        nativeSetPlatformUserAgent(userAgent());
        nativeSetRobloxVersion(version());
        if (enableBreakpad()) {
            nativeInitBreakpad(cleanupBreakpadDumps());
        }
        String proxyHost = System.getProperty("http.proxyHost", "");
        long proxyPort = Long.getLong("http.proxyPort", 0L).longValue();
        nativeSetHttpProxy(proxyHost, proxyPort);
        if (mRobloxCookiesTmpFile != null) {
            List<String> lines = Utils.readTextFile(mRobloxCookiesTmpFile.toString());
            if (lines.size() > 0) {
                Log.i(TAG, "Setting ROBLOX cookies for domain " + lines.get(0)); // DoodooBlox: never log the session cookie
                nativeSetCookiesForDomain(lines.get(0), lines.get(1));
            }
        }
        nativeInitFastLog();
    }
}
