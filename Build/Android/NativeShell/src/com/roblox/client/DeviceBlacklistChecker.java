package com.roblox.client;

import android.os.AsyncTask;
import android.util.Log;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
class DeviceBlacklistChecker extends AsyncTask<Void, Void, Void> {
    private static final String TAG = "DeviceBlacklistChecker";
    public RobloxServiceActivity mActivity;
    String mDeviceModel;
    String mResponse = null;
    JSONObject mJson = null;
    String mUrl = RobloxSettings.blacklistUrl();

    DeviceBlacklistChecker(String model, String URL, RobloxServiceActivity activity) {
        this.mActivity = null;
        this.mDeviceModel = model;
        this.mActivity = activity;
        Log.i(TAG, "Constructed URL: " + this.mUrl);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // android.os.AsyncTask
    public Void doInBackground(Void... uri) {
        this.mResponse = HttpAgent.readUrlToString(this.mUrl, null, null);
        if (this.mResponse != null) {
            try {
                this.mJson = new JSONObject(this.mResponse);
            } catch (Exception e) {
                Log.e(TAG, "Error retrieving JSON.");
            }
            Log.i(TAG, this.mResponse);
        } else {
            Log.e(TAG, "Response was null.");
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // android.os.AsyncTask
    public void onPostExecute(Void result) {
        String list;
        super.onPostExecute(result);
        if (this.mActivity != null) {
            if (this.mJson == null) {
                if (!Utils.alertIfNetworkNotConnected(this.mActivity)) {
                    Utils.sendUnexpectedError(this.mActivity, "DeviceBlacklistChecker failed");
                    Log.e(TAG, "JSON null.");
                }
                list = this.mActivity.getString(R.string.DefaultDeviceBlacklist);
            } else {
                try {
                    list = this.mJson.getString("BlacklistedDevices");
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing JSON data: " + e.getMessage());
                    Utils.sendAnalytics(this.mActivity, TAG, "Failed to parse Blacklist JSON.");
                    list = this.mActivity.getString(R.string.DefaultDeviceBlacklist);
                }
            }
            if (isOnBlacklist(this.mDeviceModel, list).booleanValue()) {
                Log.e(TAG, "Device " + this.mDeviceModel + " is on the list");
                Utils.alertExclusivelyFormatted(this.mActivity, R.string.UnsupportedDeviceSkippable, this.mDeviceModel);
            }
        }
    }

    private Boolean isOnBlacklist(String device, String list) {
        String[] splitList = list.split(",");
        for (String str : splitList) {
            if (str.equals(device)) {
                return true;
            }
        }
        return false;
    }
}
