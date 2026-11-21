package com.roblox.client;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class UpgradeCheckHelper {
    private static final String TAG = "UpgradeCheckHelper";
    private static RobloxActivity mActivity = null;
    private static UpgradeStatus upgradeStatus;

    enum UpgradeStatus {
        UnKnown,
        Recommended,
        Required,
        NotRequired
    }

    public static void checkForUpdate() {
        upgradeStatus = UpgradeStatus.UnKnown;
        new ForceUpgradeCheck().execute(new Void[0]);
    }

    public static UpgradeStatus showUpdateDialogIfRequired(RobloxActivity activity) {
        if (upgradeStatus == UpgradeStatus.Required) {
            mActivity = activity;
            new AlertDialog.Builder(activity).setTitle("ROBLOX Upgrade").setMessage(R.string.UpgradeBodyString).setPositiveButton("Upgrade", new DialogInterface.OnClickListener() { // from class: com.roblox.client.UpgradeCheckHelper.1
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialog, int which) {
                    String appPackageName = UpgradeCheckHelper.mActivity.getPackageName();
                    try {
                        UpgradeCheckHelper.mActivity.startActivity(new Intent("android.intent.action.VIEW", Uri.parse("market://details?id=" + appPackageName)));
                    } catch (ActivityNotFoundException e) {
                        UpgradeCheckHelper.mActivity.startActivity(new Intent("android.intent.action.VIEW", Uri.parse("http://play.google.com/store/apps/details?id=" + appPackageName)));
                    }
                }
            }).show();
        } else if (upgradeStatus == UpgradeStatus.Recommended) {
            mActivity = activity;
            new AlertDialog.Builder(activity).setTitle("ROBLOX Upgrade").setMessage(R.string.UpgradeBodyString).setPositiveButton("Upgrade", new DialogInterface.OnClickListener() { // from class: com.roblox.client.UpgradeCheckHelper.3
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialog, int which) {
                    String appPackageName = UpgradeCheckHelper.mActivity.getPackageName();
                    try {
                        UpgradeCheckHelper.mActivity.startActivity(new Intent("android.intent.action.VIEW", Uri.parse("market://details?id=" + appPackageName)));
                    } catch (ActivityNotFoundException e) {
                        UpgradeCheckHelper.mActivity.startActivity(new Intent("android.intent.action.VIEW", Uri.parse("http://play.google.com/store/apps/details?id=" + appPackageName)));
                    }
                }
            }).setNegativeButton("Not Now", new DialogInterface.OnClickListener() { // from class: com.roblox.client.UpgradeCheckHelper.2
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialog, int which) {
                }
            }).show();
        }
        return upgradeStatus;
    }

    static class ForceUpgradeCheck extends AsyncTask<Void, Void, Void> {
        String mStatus;
        String mResponse = null;
        JSONObject mJson = null;
        AlertDialog mAlertDialog = null;
        String upgradeCheckUrl = RobloxSettings.upgradeCheckUrl();

        ForceUpgradeCheck() {
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... uri) {
            this.mResponse = HttpAgent.readUrlToString(RobloxSettings.upgradeCheckUrl(), null, null);
            if (this.mResponse != null) {
                try {
                    this.mJson = new JSONObject(this.mResponse);
                } catch (JSONException e) {
                }
            }
            Log.i(UpgradeCheckHelper.TAG, "JSON: " + this.mResponse);
            return null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            if (this.mJson != null) {
                try {
                    JSONObject dataJsonObj = this.mJson.getJSONObject("data");
                    if (dataJsonObj != null) {
                        String status = dataJsonObj.getString("UpgradeAction");
                        if (status.equals("Required")) {
                            UpgradeStatus unused = UpgradeCheckHelper.upgradeStatus = UpgradeStatus.Required;
                        } else if (status.equals("Recommended")) {
                            UpgradeStatus unused2 = UpgradeCheckHelper.upgradeStatus = UpgradeStatus.Recommended;
                        } else {
                            UpgradeStatus unused3 = UpgradeCheckHelper.upgradeStatus = UpgradeStatus.NotRequired;
                        }
                    }
                } catch (Exception e) {
                }
            }
        }
    }
}
