package com.roblox.client;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class RobloxActivity extends RobloxServiceActivity {
    protected static final String BUILDERS_CLUB_KEY = "builders_club";
    protected static final String LAST_APP_VERSION_KEY = "last_version_code";
    protected static final String LOGGED_IN_KEY = "logged_in";
    protected static final String PASSWORD_CHECKBOX_KEY = "password_checkbox";
    protected static final String PASSWORD_KEY = "password";
    protected static final String ROBUX_KEY = "robux";
    protected static final String TAG = "RobloxActivity";
    protected static final String THUMBNAIL_KEY = "thumbnail";
    protected static final String TICKETS_KEY = "tickets";
    protected static final String USERNAME_KEY = "username";
    protected static final String WEBVIEW_URL_KEY = "webview_url";
    SharedPreferences mKeyValues = null;
    String mUsername = null;
    String mPassword = null;
    boolean mRememberPassword = false;
    boolean mLoggedIn = false;
    String mThumbnailUrl = null;
    int mRobuxBalance = 0;
    int mTicketsBalance = 0;
    boolean mIsAnyBuildersClubMember = false;

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RobloxSettings.isPhone()) {
            setRequestedOrientation(7);
        }
        HttpAgent.onCreate(this);
        this.mKeyValues = RobloxSettings.mKeyValues;
    }

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onStart() {
        super.onStart();
        // DoodooBlox: Flurry analytics removed (Flurry 6.x jar throws without FlurryAgent.init)
        writeLastVersionCode();
    }

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onStop() {
        super.onStop();
    }

    @Override // android.app.Activity
    protected void onPause() {
        String url = this.mKeyValues.getString(WEBVIEW_URL_KEY, "");
        HttpAgent.onPause(getCacheDir(), url);
        setIntent(null);
        super.onPause();
    }

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onResume() {
        super.onResume();
        HttpAgent.onResume();
        readLoginKeyValues();
        onUserInfoChanged(true);
        startUserInfoUpdate();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override // android.app.Activity
    public void onNewIntent(Intent n) {
        setIntent(n);
        super.onNewIntent(n);
    }

    @Override // android.app.Activity, android.content.ComponentCallbacks2
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        RobloxApplication.logTrimMemory(TAG, level);
    }

    protected void readLoginKeyValues() {
        this.mUsername = this.mKeyValues.getString(USERNAME_KEY, "");
        this.mPassword = this.mKeyValues.getString(PASSWORD_KEY, "");
        this.mPassword = ConfigEncryption.decrypt(this.mPassword);
        this.mRememberPassword = this.mKeyValues.getBoolean(PASSWORD_CHECKBOX_KEY, false);
        this.mLoggedIn = this.mKeyValues.getBoolean(LOGGED_IN_KEY, false);
        this.mThumbnailUrl = this.mKeyValues.getString(THUMBNAIL_KEY, "");
        this.mRobuxBalance = this.mKeyValues.getInt(ROBUX_KEY, -1);
        this.mTicketsBalance = this.mKeyValues.getInt(TICKETS_KEY, -1);
        this.mIsAnyBuildersClubMember = this.mKeyValues.getBoolean(BUILDERS_CLUB_KEY, false);
    }

    protected void writeLoginKeyValues() {
        SharedPreferences.Editor editor = this.mKeyValues.edit();
        editor.putString(USERNAME_KEY, this.mUsername);
        if (this.mRememberPassword) {
            String pw = ConfigEncryption.encrypt(this.mPassword);
            editor.putString(PASSWORD_KEY, pw);
        } else {
            editor.remove(PASSWORD_KEY);
        }
        editor.putBoolean(PASSWORD_CHECKBOX_KEY, this.mRememberPassword);
        editor.putBoolean(LOGGED_IN_KEY, this.mLoggedIn);
        editor.putString(THUMBNAIL_KEY, this.mThumbnailUrl);
        editor.putInt(ROBUX_KEY, this.mRobuxBalance);
        editor.putInt(TICKETS_KEY, this.mTicketsBalance);
        editor.putBoolean(BUILDERS_CLUB_KEY, this.mIsAnyBuildersClubMember);
        editor.apply();
    }

    protected void writeLastVersionCode() {
        SharedPreferences.Editor editor = this.mKeyValues.edit();
        int versionCode = -1;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
        }
        editor.putInt(LAST_APP_VERSION_KEY, versionCode);
        editor.apply();
    }

    protected void onUserInfoChanged(boolean trackScreen) {
    }

    protected boolean onLogin(JSONObject json, boolean trackScreen) {
        try {
            JSONObject userInfo = json.optJSONObject("UserInfo");
            if (userInfo == null) {
                userInfo = json;
            }
            this.mUsername = userInfo.getString("UserName");
            this.mRobuxBalance = userInfo.getInt("RobuxBalance");
            this.mTicketsBalance = userInfo.getInt("TicketsBalance");
            this.mIsAnyBuildersClubMember = userInfo.getBoolean("IsAnyBuildersClubMember");
            this.mThumbnailUrl = userInfo.getString("ThumbnailUrl");
            this.mLoggedIn = true;
            writeLoginKeyValues();
            onUserInfoChanged(trackScreen);
            return true;
        } catch (JSONException e) {
            Utils.alertUnexpectedError(this, "Missing User Info");
            onLogout();
            return false;
        }
    }

    protected void onLogout() {
        this.mRobuxBalance = 0;
        this.mTicketsBalance = 0;
        this.mThumbnailUrl = null;
        this.mIsAnyBuildersClubMember = false;
        this.mPassword = "";
        this.mLoggedIn = false;
        writeLoginKeyValues();
        onUserInfoChanged(true);
        new LogoutAsyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, new Void[0]);
    }

    // The sidebar shows the full-body avatar, like the site's home page: /mobileapi/userinfo leaves
    // ThumbnailUrl empty and /mobileapi/login returns the headshot, so always use the avatar render.
    static JSONObject withThumbnail(JSONObject userInfo) throws JSONException {
        if (userInfo.has("UserID")) {
            userInfo.put("ThumbnailUrl", RobloxSettings.baseUrl() + "thumbnails/avatar/" + userInfo.getLong("UserID"));
        }
        return userInfo;
    }

    protected void startUserInfoUpdate() {
        if (this.mLoggedIn) {
            UpgradeCheckHelper.checkForUpdate();
            new UserInfoAsyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, new Void[0]);
        }
    }

    private class UserInfoAsyncTask extends AsyncTask<Void, Void, Void> {
        JSONObject mJson;
        String mResponse;

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... arg0) {
            // DoodooBlox: no session, or the server no longer accepts it (401) -> "" so a remembered
            // password signs in again; otherwise the account the server reports.
            String response = RobloxSettings.hasSession() ? HttpAgent.readUrlToString(RobloxSettings.userInfoUrl(), null, null) : null;
            this.mResponse = response != null ? response : "";
            if (response != null) {
                try {
                    this.mJson = new JSONObject().put("UserInfo", withThumbnail(new JSONObject(response)));
                } catch (JSONException e) {
                }
            }
            return null;
        }

        private UserInfoAsyncTask() {
            this.mResponse = null;
            this.mJson = null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            if (this.mResponse == null) {
                if (!Utils.alertIfNetworkNotConnected(RobloxActivity.this)) {
                    Utils.sendUnexpectedError(RobloxActivity.this, "UserInfoAsyncTask failed");
                }
            } else {
                if (this.mJson == null) {
                    if (RobloxActivity.this.mRememberPassword && !RobloxActivity.this.mUsername.isEmpty() && !RobloxActivity.this.mPassword.isEmpty()) {
                        RobloxActivity.this.new LoginAsyncTask(RobloxActivity.this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
                        return;
                    } else {
                        RobloxActivity.this.onLogout();
                        Utils.alertExclusively(RobloxActivity.this, R.string.LoggedOut);
                        return;
                    }
                }
                if (RobloxActivity.this.onLogin(this.mJson, false)) {
                    Utils.sendAnalytics(RobloxActivity.this, "UserInfoAsyncTask", "OK");
                } else {
                    Utils.alertUnexpectedError(RobloxActivity.this, this.mResponse);
                }
            }
        }
    }

    class LoginAsyncTask extends AsyncTask<Void, Void, Void> {
        AlertDialog mAlertDialog;
        String mResponse = null;
        JSONObject mJson = null;

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... arg0) {
            String strEncode = "";
            String strEncode2 = "";
            try {
                strEncode = URLEncoder.encode(RobloxActivity.this.mUsername, "UTF-8");
                strEncode2 = URLEncoder.encode(RobloxActivity.this.mPassword, "UTF-8");
            } catch (UnsupportedEncodingException e) {
            }
            // DoodooBlox: DooDooBlox's mobile login checks website accounts too and answers the
            // 2014 app's own JSON (Status + UserInfo); its .ROBLOSECURITY cookie is the session.
            String urlToString = HttpAgent.readUrlToString(RobloxSettings.loginUrl(), Utils.format("username=%s&password=%s", strEncode, strEncode2), null);
            this.mResponse = urlToString;
            if (urlToString != null) {
                try {
                    this.mJson = new JSONObject(urlToString);
                    JSONObject userInfo = this.mJson.optJSONObject("UserInfo");
                    if (userInfo != null) {
                        withThumbnail(userInfo);
                    }
                } catch (JSONException e2) {
                }
            }
            return null;
        }

        LoginAsyncTask(Context c) {
            this.mAlertDialog = null;
            this.mAlertDialog = Utils.alert(c, R.string.LoggingIn);
            if (this.mAlertDialog != null) {
                this.mAlertDialog.setCanceledOnTouchOutside(false);
                this.mAlertDialog.setCancelable(false);
                this.mAlertDialog.show();
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            boolean isOk = false;
            if (this.mResponse == null) {
                if (!Utils.alertIfNetworkNotConnected(RobloxActivity.this)) {
                    Utils.alertUnexpectedError(RobloxActivity.this, "Login cannot get response");
                }
            } else if (this.mJson == null) {
                Utils.alertUnexpectedError(RobloxActivity.this, "Login cannot parse JSON");
            } else {
                try {
                    String status = this.mJson.getString("Status");
                    if (status.equals("OK")) {
                        if (RobloxActivity.this.onLogin(this.mJson, true)) {
                            Utils.sendAnalytics(RobloxActivity.this, "LoginAsyncTask", "OK");
                            isOk = true;
                        }
                    } else if (status.equals("InvalidUsername") || status.equals("InvalidPassword") || status.equals("MissingRequiredField")) {
                        Utils.alertExclusively(RobloxActivity.this, R.string.InvalidUsernameOrPw);
                    } else if (status.equals("SuccessfulLoginFloodcheck") || status.equals("FailedLoginFloodcheck") || status.equals("FailedLoginPerUserFloodcheck")) {
                        Utils.alertExclusively(RobloxActivity.this, R.string.TooManyAttempts);
                    } else if (status.equals("AccountNotApproved")) {
                        Utils.alertExclusively(RobloxActivity.this, R.string.NeedsExternalLogin);
                    } else {
                        Utils.alertUnexpectedError(RobloxActivity.this, status);
                    }
                } catch (JSONException e) {
                    Utils.alertUnexpectedError(RobloxActivity.this, "Login incomplete JSON");
                }
            }
            if (this.mAlertDialog != null) {
                this.mAlertDialog.cancel();
                this.mAlertDialog = null;
            }
            if (!isOk) {
                RobloxActivity.this.onLogout();
            }
        }
    }

    private class LogoutAsyncTask extends AsyncTask<Void, Void, Void> {
        String mResponse;

        private LogoutAsyncTask() {
            this.mResponse = null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... arg0) {
            this.mResponse = HttpAgent.readUrlToString(RobloxSettings.logoutUrl(), "", null);
            // DoodooBlox: drop the site's own forms login too, or its pages stay signed in as the old account.
            CookieManager.getInstance().removeAllCookie();
            return null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            Log.i(RobloxActivity.TAG, "Logout: " + this.mResponse);
            if (this.mResponse == null && !Utils.alertIfNetworkNotConnected(RobloxActivity.this)) {
                Utils.alertUnexpectedError(RobloxActivity.this, "LogoutAsyncTask failed");
            }
        }
    }
}
