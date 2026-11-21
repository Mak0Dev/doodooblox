package com.roblox.client;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebBackForwardList;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public class ActivityWebView extends RobloxActivity {
    private static final String TAG = "webview";
    private ImageButton mGridImageButton = null;
    private TextView mUrlBar = null;
    private LinearLayout mRobuxLayout = null;
    private LinearLayout mTicketsLayout = null;
    private TextView mRobuxBalanceTextView = null;
    private TextView mTicketsTextView = null;
    private WebView mWebView = null;
    private String mWebViewUrl = null;
    private String mPlaceId = null;
    private Handler mHandler = null;
    private boolean mIsForeground = false;
    private WebViewClientEmbedded mWebViewClientEmbedded = null;

    private class WebViewClientEmbedded extends WebViewClient {
        private WebViewClientEmbedded() {
        }

        @Override // android.webkit.WebViewClient
        public boolean shouldOverrideUrlLoading(WebView view, String urlString) {
            String queryParameter;
            UpgradeCheckHelper.UpgradeStatus status;
            Log.i(ActivityWebView.TAG, "-> " + urlString);
            if (urlString.startsWith("karblox-player:")) {
                String strSubstring = urlString.substring(15);
                int iIndexOf = strSubstring.indexOf(43);
                if (iIndexOf > 0) {
                    strSubstring = strSubstring.substring(0, iIndexOf);
                }
                urlString = "http://gametest1.doodooblox.wtf/games/start?placeid=" + strSubstring;
            } else if (urlString.indexOf("/guest/play") >= 0) {
                String queryParameter2 = Uri.parse(urlString).getQueryParameter("placeId");
                if (queryParameter2 != null) {
                    urlString = "http://gametest1.doodooblox.wtf/games/start?placeid=" + queryParameter2;
                }
            } else if (urlString.indexOf("/Doodoo/browser-launch") >= 0 && (queryParameter = Uri.parse(urlString).getQueryParameter("placeId")) != null) {
                urlString = "http://gametest1.doodooblox.wtf/games/start?placeid=" + queryParameter;
            }
            if (!Utils.alertIfNetworkNotConnected(ActivityWebView.this)) {
                RobloxApplication app = (RobloxApplication) ActivityWebView.this.getApplication();
                if (app.checkShowCriticalError()) {
                    Log.i(ActivityWebView.TAG, "Trying to use WebView after critical error.");
                } else {
                    Utils.sendAnalytics(ActivityWebView.this, "WebView", urlString);
                    if (urlString.indexOf("/games/start?") > -1) {
                        String reason = RobloxSettings.deviceNotSupportedString();
                        if (reason != null && !RobloxSettings.deviceNotSupportedSkippable()) {
                            Utils.alertExclusivelyFormatted(ActivityWebView.this, R.string.UnsupportedDevice, reason);
                        } else {
                            Uri uriObject = Uri.parse(urlString);
                            ActivityWebView.this.mPlaceId = uriObject.getQueryParameter("placeid");
                            if (ActivityWebView.this.mPlaceId == null) {
                                ActivityWebView.this.mPlaceId = uriObject.getQueryParameter("userID");
                                if (ActivityWebView.this.mPlaceId != null) {
                                    ActivityWebView.this.mPlaceId = "-" + ActivityWebView.this.mPlaceId;
                                    Log.i(ActivityWebView.TAG, Utils.format("Signalling Service PlaceId:%s", ActivityWebView.this.mPlaceId));
                                    status = UpgradeCheckHelper.showUpdateDialogIfRequired(ActivityWebView.this);
                                    if (status != UpgradeCheckHelper.UpgradeStatus.Recommended && status != UpgradeCheckHelper.UpgradeStatus.Required) {
                                        ActivityWebView.this.doNotifyService(3, Integer.parseInt(ActivityWebView.this.mPlaceId));
                                        HttpAgent.flushToDisk(ActivityWebView.this.getCacheDir(), urlString);
                                    }
                                } else {
                                    Utils.alertUnexpectedError(ActivityWebView.this, "Missing placeid or userID");
                                }
                            } else {
                                Log.i(ActivityWebView.TAG, Utils.format("Signalling Service PlaceId:%s", ActivityWebView.this.mPlaceId));
                                status = UpgradeCheckHelper.showUpdateDialogIfRequired(ActivityWebView.this);
                                if (status != UpgradeCheckHelper.UpgradeStatus.Recommended) {
                                    ActivityWebView.this.doNotifyService(3, Integer.parseInt(ActivityWebView.this.mPlaceId));
                                    HttpAgent.flushToDisk(ActivityWebView.this.getCacheDir(), urlString);
                                }
                            }
                        }
                    } else if (urlString.indexOf("mobile-app-upgrades/buy?") <= -1) {
                        ActivityWebView.this.mUrlBar.setText(urlString);
                        view.loadUrl(urlString);
                        ActivityWebView.this.mWebViewUrl = urlString;
                    } else if (ActivityWebView.this.mStoreMgr == null) {
                        Utils.alertExclusively(ActivityWebView.this, "Please upgrade your Android Version to allow Purchasing");
                        Utils.sendAnalytics(ActivityWebView.this, "WebView", urlString + "/PurchaseFailedDueToOldAndroidVersion");
                    } else {
                        ActivityWebView.this.mKeyValues.edit();
                        ActivityWebView.this.mKeyValues.edit().putBoolean("isReturningFromPurchase", true).apply();
                        if (!ActivityWebView.this.mStoreMgr.doInAppPurchaseForUrl(ActivityWebView.this, urlString, ActivityWebView.this.mUsername)) {
                            Utils.alertExclusively(ActivityWebView.this, "Please setup Google Play Store to make purchases.");
                            Utils.sendAnalytics(ActivityWebView.this, "StoreManager", "PurchaseFailedDueToGooglePlayStoreNotSetup");
                        }
                    }
                }
            }
            return true;
        }
    }

    @Override // com.roblox.client.RobloxActivity, com.roblox.client.RobloxServiceActivity, android.app.Activity
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);
        Log.i(TAG, "in WebView.onCreate");
        RobloxApplication app = (RobloxApplication) getApplication();
        if (app.checkShowCriticalError()) {
            Log.i(TAG, "Trying to create WebView after critical error.");
            return;
        }
        this.mHandler = new Handler(Looper.getMainLooper());
        this.mGridImageButton = (ImageButton) findViewById(R.id.webview_grid);
        this.mUrlBar = (TextView) findViewById(R.id.webview_urlbar);
        this.mRobuxLayout = (LinearLayout) findViewById(R.id.webview_tickets_layout);
        this.mTicketsLayout = (LinearLayout) findViewById(R.id.webview_robux_layout);
        this.mRobuxBalanceTextView = (TextView) findViewById(R.id.robux_balance);
        this.mTicketsTextView = (TextView) findViewById(R.id.tickets_balance);
        this.mGridImageButton.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.ActivityWebView.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ActivityWebView.this.goBackToShell();
            }
        });
        this.mWebViewClientEmbedded = new WebViewClientEmbedded();
        this.mWebView = (WebView) findViewById(R.id.web_view);
        this.mWebView.setWebViewClient(this.mWebViewClientEmbedded);
        this.mWebView.getSettings().setJavaScriptEnabled(true);
        this.mWebView.getSettings().setUserAgentString(RobloxSettings.userAgent());
        this.mWebView.getSettings().setCacheMode(-1);
        this.mUrlBar.setVisibility(RobloxSettings.isInternalBuild() ? 0 : 4);
        Utils.alertIfNetworkNotConnected(this);
        String baseUrl = RobloxSettings.baseUrl();
        if (!baseUrl.equals("http://gametest1.doodooblox.wtf/") && !baseUrl.equals("http://gametest1.doodooblox.wtf/")) {
            Utils.alertUnexpectedError(this, "baseUrl: " + baseUrl);
        }
        if (!RobloxSettings.enableBreakpad()) {
            Utils.alertUnexpectedError(this, "Breakpad Disabled");
        }
    }

    @Override // com.roblox.client.RobloxActivity, com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onResume() {
        File file = new File(getCacheDir(), RobloxSettings.exceptionReasonFilename());
        if (file.exists()) {
            List<String> lines = Utils.readTextFile(file.toString());
            file.delete();
            Utils.alertUnexpectedError(this, lines.get(0));
        }
        Intent incomingIntent = getIntent();
        String url = incomingIntent != null ? incomingIntent.getStringExtra("roblox_url") : null;
        if (this.mKeyValues.getBoolean("isReturningFromPurchase", false)) {
            url = RobloxSettings.buildersClubUrl();
            this.mKeyValues.edit().remove("isReturningFromPurchase").commit();
        } else if (url == null) {
            if (RobloxSettings.useWebURLOverride() && RobloxSettings.webViewURLOverride() != null) {
                this.mWebViewUrl = RobloxSettings.webViewURLOverride();
            } else {
                String parcelString = this.mKeyValues.getString("parcel", "");
                if (parcelString != null) {
                    Parcel p = Parcel.obtain();
                    try {
                        byte[] data = Base64.decode(parcelString, 0);
                        p.unmarshall(data, 0, data.length);
                        p.setDataPosition(0);
                        Bundle bundle = p.readBundle();
                        if (bundle != null) {
                            String str = Build.BRAND;
                            this.mWebView.restoreState(bundle);
                            this.mWebViewUrl = this.mWebView.getUrl();
                        } else {
                            this.mWebViewUrl = RobloxSettings.gamesUrl();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error reading in WebView bundle.");
                    } finally {
                        p.recycle();
                    }
                }
            }
            url = this.mWebViewUrl;
        } else {
            this.mWebViewUrl = url;
        }
        Log.i(TAG, "=> " + url);
        Utils.sendAnalytics(this, "WebView", url);
        this.mUrlBar.setText(url);
        String displayedUrl = this.mWebView.getUrl();
        if (displayedUrl == null || !displayedUrl.equals(url)) {
            this.mWebView.loadUrl(url);
        }
        Utils.sendAnalyticsScreen(this, "WebViewScreen");
        Utils.onRateMeMaybe(this);
        this.mIsForeground = true;
        this.mHandler.postDelayed(new Runnable() { // from class: com.roblox.client.ActivityWebView.2
            @Override // java.lang.Runnable
            public void run() {
                if (ActivityWebView.this.mIsForeground) {
                    Utils.killBackgroundProcesses(ActivityWebView.this);
                }
            }
        }, 2000L);
        if (RobloxSettings.isInternalBuild() && incomingIntent != null && incomingIntent.getBooleanExtra("forceLaunchDebug", false)) {
            String placeUrl = RobloxSettings.baseUrl() + "/games/start?placeid=" + incomingIntent.getStringExtra("roblox_placeid");
            this.mWebViewClientEmbedded.shouldOverrideUrlLoading(this.mWebView, placeUrl);
        }
        super.onResume();
        this.mStoreMgr.handleActivityResume();
    }

    @Override // com.roblox.client.RobloxActivity, android.app.Activity
    protected void onPause() {
        super.onPause();
        this.mIsForeground = false;
        writeWebViewUrl();
    }

    @Override // android.app.Activity, android.view.KeyEvent.Callback
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == 0) {
            switch (keyCode) {
                case 4:
                    if (this.mWebView.canGoBack()) {
                        this.mWebView.goBack();
                    } else {
                        goBackToShell();
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override // com.roblox.client.RobloxServiceActivity
    protected void onServiceNotification(int notification, int extra) {
        if (notification == 3) {
            String extraStr = String.valueOf(extra);
            if (this.mPlaceId == null || !this.mPlaceId.equals(extraStr)) {
                Log.i(TAG, Utils.format("Mismatched place ID %s != %s ", this.mPlaceId, extraStr));
                return;
            }
            int pid = Process.myPid();
            boolean debuggerAttached = Debug.isDebuggerConnected();
            Object[] objArr = new Object[3];
            objArr[0] = this.mPlaceId;
            objArr[1] = Integer.valueOf(pid);
            objArr[2] = debuggerAttached ? "attached" : "none";
            Log.i(TAG, Utils.format("Launching PlaceId:%s Pid:%d Debuger:%s", objArr));
            Intent intent = new Intent(this, (Class<?>) ActivityGlView.class);
            intent.setFlags(335544320);
            intent.putExtra("roblox_placeid", this.mPlaceId);
            intent.putExtra("roblox_launcher_pid", pid);
            intent.putExtra("roblox_launcher_debugger_attached", debuggerAttached);
            intent.putExtra("roblox_back_activity", "ActivityWebView");
            startActivity(intent);
            finish();
        }
    }

    @Override // com.roblox.client.RobloxActivity
    public void onUserInfoChanged(boolean trackScreen) {
        this.mRobuxLayout.setVisibility(this.mLoggedIn ? 0 : 4);
        this.mTicketsLayout.setVisibility(this.mLoggedIn ? 0 : 4);
        this.mRobuxBalanceTextView.setText(" " + this.mRobuxBalance);
        this.mTicketsTextView.setText(" " + this.mTicketsBalance);
    }

    protected void writeWebViewUrl() {
        SharedPreferences.Editor editor = this.mKeyValues.edit();
        Bundle webBundle = new Bundle();
        WebBackForwardList testList = this.mWebView.saveState(webBundle);
        if (testList != null) {
            Parcel parcel = Parcel.obtain();
            webBundle.writeToParcel(parcel, 0);
            String serialized = null;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bos.write(parcel.marshall());
                serialized = Base64.encodeToString(bos.toByteArray(), 0);
                Log.i(TAG, "Writing web history");
            } catch (Exception e) {
                Log.e(TAG, "Error serializing WebView bundle.");
            } finally {
                parcel.recycle();
            }
            if (serialized != null) {
                editor.putString("parcel", serialized);
                editor.apply();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void goBackToShell() {
        Intent intent = new Intent(this, (Class<?>) ActivityNativeMain.class);
        intent.putExtra("roblox_back_activity", "ActivityWebView");
        startActivity(intent);
        finish();
    }
}
