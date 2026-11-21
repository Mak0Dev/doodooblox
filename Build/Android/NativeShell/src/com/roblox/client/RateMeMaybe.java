package com.roblox.client;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import com.google.android.gms.common.GooglePlayServicesUtil;

/* JADX INFO: loaded from: classes.dex */
public class RateMeMaybe implements RateMeMaybeFragment.RMMFragInterface {
    private static final String TAG = "RateMeMaybe";
    private Activity mActivity;
    private String mDialogMessage;
    private String mDialogTitle;
    private int mIcon;
    private OnRMMUserChoiceListener mListener;
    private String mNegativeBtn;
    private String mNeutralBtn;
    private String mPositiveBtn;
    private SharedPreferences mPreferences;
    private int mMinLaunchesUntilInitialPrompt = 0;
    private int mMinDaysUntilInitialPrompt = 0;
    private int mMinLaunchesUntilNextPrompt = 0;
    private int mMinDaysUntilNextPrompt = 0;
    private Boolean mHandleCancelAsNeutral = true;
    private Boolean mRunWithoutPlayStore = false;

    public interface OnRMMUserChoiceListener {
        void handleNegative();

        void handleNeutral();

        void handlePositive();
    }

    public RateMeMaybe(Activity activity) {
        this.mActivity = activity;
        this.mPreferences = this.mActivity.getSharedPreferences(PREF.NAME, 0);
    }

    public void setDialogTitle(String dialogTitle) {
        this.mDialogTitle = dialogTitle;
    }

    public String getDialogTitle() {
        return this.mDialogTitle == null ? "Rate " + getApplicationName() : this.mDialogTitle;
    }

    public void setDialogMessage(String dialogMessage) {
        this.mDialogMessage = dialogMessage;
    }

    public String getDialogMessage() {
        return this.mDialogMessage == null ? "If you like using " + getApplicationName() + ", it would be great if you took a moment to rate it in the Play Store. Thank you!" : this.mDialogMessage.replace("%totalLaunchCount%", String.valueOf(this.mPreferences.getInt(PREF.TOTAL_LAUNCH_COUNT, 0)));
    }

    public String getPositiveBtn() {
        return this.mPositiveBtn == null ? "Rate it" : this.mPositiveBtn;
    }

    public void setPositiveBtn(String positiveBtn) {
        this.mPositiveBtn = positiveBtn;
    }

    public String getNeutralBtn() {
        return this.mNeutralBtn == null ? "Not now" : this.mNeutralBtn;
    }

    public void setNeutralBtn(String neutralBtn) {
        this.mNeutralBtn = neutralBtn;
    }

    public String getNegativeBtn() {
        return this.mNegativeBtn == null ? "Never" : this.mNegativeBtn;
    }

    public void setNegativeBtn(String negativeBtn) {
        this.mNegativeBtn = negativeBtn;
    }

    public void setIcon(int customIcon) {
        this.mIcon = customIcon;
    }

    public int getIcon() {
        return this.mIcon;
    }

    public void setPromptMinimums(int minLaunchesUntilInitialPrompt, int minDaysUntilInitialPrompt, int minLaunchesUntilNextPrompt, int minDaysUntilNextPrompt) {
        this.mMinLaunchesUntilInitialPrompt = minLaunchesUntilInitialPrompt;
        this.mMinDaysUntilInitialPrompt = minDaysUntilInitialPrompt;
        this.mMinLaunchesUntilNextPrompt = minLaunchesUntilNextPrompt;
        this.mMinDaysUntilNextPrompt = minDaysUntilNextPrompt;
    }

    public void setHandleCancelAsNeutral(Boolean handleCancelAsNeutral) {
        this.mHandleCancelAsNeutral = handleCancelAsNeutral;
    }

    public void setAdditionalListener(OnRMMUserChoiceListener listener) {
        this.mListener = listener;
    }

    public void setRunWithoutPlayStore(Boolean runWithoutPlayStore) {
        this.mRunWithoutPlayStore = runWithoutPlayStore;
    }

    public static void resetData(Activity activity) {
        activity.getSharedPreferences(PREF.NAME, 0).edit().clear().commit();
        Log.d(TAG, "Cleared RateMeMaybe shared preferences.");
    }

    private void showDialog() {
        FragmentManager fm = this.mActivity.getFragmentManager();
        if (fm.findFragmentByTag("rmmFragment") == null) {
            RateMeMaybeFragment frag = new RateMeMaybeFragment();
            frag.setData(getIcon(), getDialogTitle(), getDialogMessage(), getPositiveBtn(), getNeutralBtn(), getNegativeBtn(), this);
            try {
                frag.show(fm, "rmmFragment");
            } catch (IllegalStateException e) {
            }
        }
    }

    public void forceShow() {
        showDialog();
    }

    public void run() {
        if (!this.mPreferences.getBoolean("PREF_DONT_SHOW_AGAIN", false)) {
            if (!isPlayStoreInstalled().booleanValue()) {
                Log.d(TAG, "No Play Store installed on device.");
                if (!this.mRunWithoutPlayStore.booleanValue()) {
                    return;
                }
            }
            SharedPreferences.Editor editor = this.mPreferences.edit();
            int totalLaunchCount = this.mPreferences.getInt(PREF.TOTAL_LAUNCH_COUNT, 0) + 1;
            editor.putInt(PREF.TOTAL_LAUNCH_COUNT, totalLaunchCount);
            long currentMillis = System.currentTimeMillis();
            long timeOfAbsoluteFirstLaunch = this.mPreferences.getLong(PREF.TIME_OF_ABSOLUTE_FIRST_LAUNCH, 0L);
            if (timeOfAbsoluteFirstLaunch == 0) {
                timeOfAbsoluteFirstLaunch = currentMillis;
                editor.putLong(PREF.TIME_OF_ABSOLUTE_FIRST_LAUNCH, timeOfAbsoluteFirstLaunch);
            }
            long timeOfLastPrompt = this.mPreferences.getLong(PREF.TIME_OF_LAST_PROMPT, 0L);
            int launchesSinceLastPrompt = this.mPreferences.getInt(PREF.LAUNCHES_SINCE_LAST_PROMPT, 0) + 1;
            editor.putInt(PREF.LAUNCHES_SINCE_LAST_PROMPT, launchesSinceLastPrompt);
            if (totalLaunchCount < this.mMinLaunchesUntilInitialPrompt || currentMillis - timeOfAbsoluteFirstLaunch < ((long) this.mMinDaysUntilInitialPrompt) * 86400000) {
                editor.commit();
                return;
            }
            if (timeOfLastPrompt == 0 || (launchesSinceLastPrompt >= this.mMinLaunchesUntilNextPrompt && currentMillis - timeOfLastPrompt >= ((long) this.mMinDaysUntilNextPrompt) * 86400000)) {
                editor.putLong(PREF.TIME_OF_LAST_PROMPT, currentMillis);
                editor.putInt(PREF.LAUNCHES_SINCE_LAST_PROMPT, 0);
                editor.commit();
                showDialog();
                return;
            }
            editor.commit();
        }
    }

    @Override // com.roblox.client.RateMeMaybeFragment.RMMFragInterface
    public void _handleCancel() {
        if (this.mHandleCancelAsNeutral.booleanValue()) {
            _handleNeutralChoice();
        } else {
            _handleNegativeChoice();
        }
    }

    @Override // com.roblox.client.RateMeMaybeFragment.RMMFragInterface
    public void _handleNegativeChoice() {
        SharedPreferences.Editor editor = this.mPreferences.edit();
        editor.putBoolean("PREF_DONT_SHOW_AGAIN", true);
        editor.commit();
        if (this.mListener != null) {
            this.mListener.handleNegative();
        }
    }

    @Override // com.roblox.client.RateMeMaybeFragment.RMMFragInterface
    public void _handleNeutralChoice() {
        if (this.mListener != null) {
            this.mListener.handleNeutral();
        }
    }

    @Override // com.roblox.client.RateMeMaybeFragment.RMMFragInterface
    public void _handlePositiveChoice() {
        SharedPreferences.Editor editor = this.mPreferences.edit();
        editor.putBoolean("PREF_DONT_SHOW_AGAIN", true);
        editor.commit();
        try {
            this.mActivity.startActivity(new Intent("android.intent.action.VIEW", Uri.parse("market://details?id=" + this.mActivity.getPackageName())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this.mActivity, "Could not launch Play Store!", 0).show();
        }
        if (this.mListener != null) {
            this.mListener.handlePositive();
        }
    }

    private String getApplicationName() {
        PackageManager pm = this.mActivity.getApplicationContext().getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(this.mActivity.getPackageName(), 0);
            String appName = (String) pm.getApplicationLabel(ai);
            return appName;
        } catch (PackageManager.NameNotFoundException e) {
            return "(unknown)";
        }
    }

    private Boolean isPlayStoreInstalled() {
        PackageManager pacman = this.mActivity.getPackageManager();
        try {
            pacman.getApplicationInfo(GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static class PREF {
        private static final String DONT_SHOW_AGAIN = "PREF_DONT_SHOW_AGAIN";
        public static final String LAUNCHES_SINCE_LAST_PROMPT = "PREF_LAUNCHES_SINCE_LAST_PROMPT";
        public static final String NAME = "rate_me_maybe";
        public static final String TIME_OF_ABSOLUTE_FIRST_LAUNCH = "PREF_TIME_OF_ABSOLUTE_FIRST_LAUNCH";
        public static final String TIME_OF_LAST_PROMPT = "PREF_TIME_OF_LAST_PROMPT";
        public static final String TOTAL_LAUNCH_COUNT = "PREF_TOTAL_LAUNCH_COUNT";

        PREF() {
        }
    }
}
