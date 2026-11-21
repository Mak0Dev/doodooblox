package com.roblox.client;

import android.os.AsyncTask;
import android.util.Log;
import android.webkit.WebView;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: compiled from: ActivityNativeMain.java */
/* JADX INFO: loaded from: classes.dex */
class SiteAlertAsyncTask extends AsyncTask<Void, Void, Void> {
    WebView mWebViewReference;
    String mResponse = null;
    String TAG = "SiteAlertAsyncTask";

    SiteAlertAsyncTask(WebView ref) {
        this.mWebViewReference = null;
        this.mWebViewReference = ref;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // android.os.AsyncTask
    public Void doInBackground(Void... arg0) {
        String baseUrl = RobloxSettings.isPhone() ? RobloxSettings.baseUrl().replace("m.", "api.") : RobloxSettings.baseUrl().replace("www.", "api.");
        Log.i(this.TAG, baseUrl + "alerts/alert-info");
        this.mResponse = HttpAgent.readUrlToString(baseUrl + "alerts/alert-info", null, null);
        return null;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // android.os.AsyncTask
    public void onPostExecute(Void result) {
        String finalText;
        super.onPostExecute(result);
        Log.i(this.TAG, "Response: " + this.mResponse);
        if (this.mResponse != null) {
            try {
                JSONObject json = new JSONObject(this.mResponse);
                if (json != null && json.getBoolean("IsVisible")) {
                    String finalText2 = json.getString("Text");
                    if (finalText2.contains("[alertColor")) {
                        if (RobloxSettings.isPhone()) {
                            finalText = finalText2.replaceAll(Pattern.quote("[alertColor=") + "(.*?)" + Pattern.quote("]"), "<body bgColor=\"$1\"><marquee>") + "</marquee></body>";
                        } else {
                            finalText = finalText2.replaceAll(Pattern.quote("[alertColor=") + "(.*?)" + Pattern.quote("]"), "<body bgColor=\"$1\">") + "</body>";
                        }
                    } else if (RobloxSettings.isPhone()) {
                        finalText = "<body bgColor=\"#FF3030\"><marquee>" + finalText2 + "</marquee></body>";
                    } else {
                        finalText = "<body bgColor=\"#FF3030\">" + finalText2 + "</body>";
                    }
                    this.mWebViewReference.setVisibility(0);
                    this.mWebViewReference.loadDataWithBaseURL(null, finalText, "text/html", "utf-8", null);
                    return;
                }
                this.mWebViewReference.setVisibility(8);
                return;
            } catch (JSONException e) {
                Log.e(this.TAG, e.toString());
                this.mWebViewReference.setVisibility(8);
                return;
            }
        }
        Log.e(this.TAG, "Response was null.");
        this.mWebViewReference.setVisibility(8);
    }
}
