package com.roblox.client;

import android.app.Application;
import android.content.Context;
import android.support.multidex.MultiDex;
import android.util.Log;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;
import java.io.IOException;

/* JADX INFO: loaded from: classes.dex */
public class RobloxApplication extends Application {
    private static String TAG = "roblox.app";
    static String mCriticalError = null;
    private Tracker mAndroidTracker = null;

    public void setCriticalErrorOccured(String msg) {
        mCriticalError = msg;
        Log.e(TAG, "************************************************************");
        Log.e(TAG, "***  CriticalError: " + mCriticalError);
        Log.e(TAG, "************************************************************");
        throw new RuntimeException(msg);
    }

    public boolean checkShowCriticalError() {
        if (mCriticalError != null) {
            Log.e(TAG, "***  CriticalError: " + mCriticalError);
        }
        return mCriticalError != null;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // DoodooBlox: built with multidex (play-services 7.0) - needed below API 21
        MultiDex.install(this);
    }

    @Override // android.app.Application
    public void onCreate() {
        super.onCreate();
        try {
            RobloxSettings.initConfig(getApplicationContext());
        } catch (IOException e) {
            setCriticalErrorOccured(e.getMessage());
        }
        String ua = RobloxSettings.userAgent();
        Log.i(TAG, ua);
    }

    synchronized Tracker getAndroidTracker() {
        if (this.mAndroidTracker == null) {
            GoogleAnalytics analytics = GoogleAnalytics.getInstance(this);
            this.mAndroidTracker = analytics.newTracker(R.xml.android_tracker);
        }
        return this.mAndroidTracker;
    }

    static void logTrimMemory(String TAG2, int level) {
        switch (level) {
            case 20:
                Log.w(TAG2, "TRIM_MEMORY_UI_HIDDEN");
                break;
            case 40:
                Log.w(TAG2, "TRIM_MEMORY_BACKGROUND");
                break;
            case 60:
                Log.w(TAG2, "TRIM_MEMORY_MODERATE");
                break;
            case 80:
                Log.w(TAG2, "TRIM_MEMORY_COMPLETE");
                break;
        }
    }
}
