package com.roblox.client;

import android.app.Activity;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.ViewFlipper;
import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.jirbo.adcolony.AdColony;
import com.mobileapptracker.MobileAppTracker;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class ActivityNativeMain extends RobloxActivity implements FragmentSignUp.SignUpCallbacks {
    public static WebView mSiteAlertWebView = null;
    Activity mActivity = null;
    ViewFlipper mViewFlipper = null;
    FragmentSignUp mFragmentSignUp = null;
    EditText mUsernameEditText = null;
    EditText mPasswordEditText = null;
    CheckBox mRememberPasswordCheckBox = null;
    View mFocusableView = null;
    ViewFlipper mSidebarViewFlipper = null;
    ImageView mThumbnailUrlImageView = null;
    TextView mUsernameTextView = null;
    TextView mRobuxBalanceTextView = null;
    TextView mTicketsTextView = null;
    EditText mSearchEditText = null;
    ImageView mLogoutImageView = null;
    ImageView mLogoutImageView2 = null;
    Button mSidebarSignUp = null;
    Button mSidebarLogin = null;
    Spinner mSpinnerEnvironment = null;
    Button mLaunchPlaceDebugButton = null;
    EditText mPlaceIdDebugEditText = null;
    MobileAppTracker mobileAppTracker = null;

    @Override // com.roblox.client.RobloxActivity, com.roblox.client.RobloxServiceActivity, android.app.Activity
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RobloxSettings.isPhone()) {
            setContentView(R.layout.activity_native_main_phone);
        } else {
            setContentView(R.layout.activity_native_main);
        }
        this.mActivity = this;
        this.mViewFlipper = (ViewFlipper) findViewById(R.id.view_flipper);
        // DoodooBlox: Facebook analytics removed
        // DoodooBlox: MobileAppTracker disabled (dead service, only spams SSL errors)
        initializeAdColony();
        this.mUsernameEditText = (EditText) findViewById(R.id.username);
        this.mUsernameEditText.setText(this.mUsername);
        this.mPasswordEditText = (EditText) findViewById(R.id.password);
        this.mPasswordEditText.setText(this.mPassword);
        this.mRememberPasswordCheckBox = (CheckBox) findViewById(R.id.remember_password);
        this.mRememberPasswordCheckBox.setChecked(this.mRememberPassword);
        this.mFocusableView = findViewById(R.id.focusable_view);
        this.mSidebarViewFlipper = (ViewFlipper) findViewById(R.id.sidebar_flipper);
        this.mThumbnailUrlImageView = (ImageView) findViewById(R.id.thumbnail_url);
        this.mUsernameTextView = (TextView) findViewById(R.id.username_textview);
        this.mRobuxBalanceTextView = (TextView) findViewById(R.id.robux_balance);
        this.mTicketsTextView = (TextView) findViewById(R.id.tickets_balance);
        this.mSearchEditText = (EditText) findViewById(R.id.search);
        this.mLogoutImageView = (ImageView) findViewById(R.id.logout);
        this.mLogoutImageView2 = (ImageView) findViewById(R.id.logout2);
        this.mSidebarSignUp = (Button) findViewById(R.id.sidebar_sign_up);
        this.mSidebarLogin = (Button) findViewById(R.id.sidebar_login);
        mSiteAlertWebView = (WebView) findViewById(R.id.siteAlertWebView);
        mSiteAlertWebView.setVisibility(8);
        this.mSpinnerEnvironment = (Spinner) findViewById(R.id.spinner_environment);
        this.mLaunchPlaceDebugButton = (Button) findViewById(R.id.buttonLaunchPlace);
        this.mPlaceIdDebugEditText = (EditText) findViewById(R.id.editTextPlaceID);
        if (RobloxSettings.isInternalBuild()) {
            ArrayAdapter<CharSequence> envAdapter = ArrayAdapter.createFromResource(this.mActivity, RobloxSettings.isTablet() ? R.array.EnvironmentArray : R.array.EnvironmentArrayMobile, android.R.layout.simple_spinner_item);
            envAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            this.mSpinnerEnvironment.setAdapter((SpinnerAdapter) envAdapter);
            this.mSpinnerEnvironment.setSelection(0, false);
            this.mSpinnerEnvironment.post(new Runnable() { // from class: com.roblox.client.ActivityNativeMain.1
                @Override // java.lang.Runnable
                public void run() {
                    ActivityNativeMain.this.mSpinnerEnvironment.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.roblox.client.ActivityNativeMain.1.1
                        @Override // android.widget.AdapterView.OnItemSelectedListener
                        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                            if (RobloxSettings.isTablet()) {
                                RobloxSettings.setBaseUrlDebug(parent.getItemAtPosition(pos).toString());
                            } else {
                                RobloxSettings.setBaseMobileUrlDebug(parent.getItemAtPosition(pos).toString());
                            }
                        }

                        @Override // android.widget.AdapterView.OnItemSelectedListener
                        public void onNothingSelected(AdapterView<?> parent) {
                        }
                    });
                }
            });
            if (this.mLaunchPlaceDebugButton != null) {
                this.mLaunchPlaceDebugButton.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.ActivityNativeMain.2
                    @Override // android.view.View.OnClickListener
                    public void onClick(View view) {
                        if (ActivityNativeMain.this.mPlaceIdDebugEditText != null && ActivityNativeMain.this.mPlaceIdDebugEditText.getText().toString() != "") {
                            Intent intent = new Intent(ActivityNativeMain.this, (Class<?>) ActivityWebView.class);
                            intent.putExtra("roblox_placeid", ActivityNativeMain.this.mPlaceIdDebugEditText.getText().toString());
                            intent.putExtra("forceLaunchDebug", true);
                            ActivityNativeMain.this.startActivity(intent);
                            ActivityNativeMain.this.finish();
                        }
                    }
                });
            }
        } else {
            this.mSpinnerEnvironment.setVisibility(8);
            this.mPlaceIdDebugEditText.setVisibility(8);
            this.mLaunchPlaceDebugButton.setVisibility(8);
        }
        UpgradeCheckHelper.checkForUpdate();
        this.mThumbnailUrlImageView.setImageDrawable(getResources().getDrawable(R.drawable.default_thumbnail));
        this.mUsernameEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.roblox.client.ActivityNativeMain.3
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != 5 && event.getKeyCode() != 66) {
                    return false;
                }
                ActivityNativeMain.this.mPasswordEditText.requestFocus();
                return true;
            }
        });
        this.mPasswordEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.roblox.client.ActivityNativeMain.4
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != 2 && event.getKeyCode() != 66) {
                    return false;
                }
                ActivityNativeMain.this.onExecuteLogin(null);
                return true;
            }
        });
        this.mSearchEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.roblox.client.ActivityNativeMain.5
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != 2 && event.getKeyCode() != 66) {
                    return false;
                }
                String t = "";
                try {
                    t = URLEncoder.encode(v.getText().toString(), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                }
                Intent intent = new Intent(ActivityNativeMain.this, (Class<?>) ActivityWebView.class);
                intent.putExtra("roblox_url", RobloxSettings.searchGamesUrl(t));
                intent.putExtra("roblox_back_activity", "ActivityNativeMain");
                ActivityNativeMain.this.startActivity(intent);
                ActivityNativeMain.this.finish();
                return true;
            }
        });
        View.OnClickListener logoutOnClick = new View.OnClickListener() { // from class: com.roblox.client.ActivityNativeMain.6
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ActivityNativeMain.this.onLogout();
            }
        };
        this.mLogoutImageView.setOnClickListener(logoutOnClick);
        if (this.mLogoutImageView2 != null) {
            this.mLogoutImageView2.setOnClickListener(logoutOnClick);
        }
        if (this.mSidebarSignUp != null) {
            this.mSidebarSignUp.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.ActivityNativeMain.7
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    ActivityNativeMain.this.mViewFlipper.setDisplayedChild(0);
                    ActivityNativeMain.this.onSignUp(null);
                }
            });
        }
        if (this.mSidebarLogin != null) {
            this.mSidebarLogin.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.ActivityNativeMain.8
                @Override // android.view.View.OnClickListener
                public void onClick(View view) {
                    ActivityNativeMain.this.mViewFlipper.setDisplayedChild(0);
                    Utils.sendAnalyticsScreen(ActivityNativeMain.this, "LoginScreen");
                }
            });
        }
    }

    @Override // com.roblox.client.RobloxActivity, com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onResume() {
        Intent i;
        super.onResume();
        Utils.killBackgroundProcesses(this);
        onUserInfoChanged(true);
        if (!this.mLoggedIn && (i = getIntent()) != null && i.hasExtra("roblox_back_activity")) {
            onPlayNow(null);
        }
        if (this.mobileAppTracker != null) {
            this.mobileAppTracker.setReferralSources(this);
            this.mobileAppTracker.measureSession();
        }
        new SiteAlertAsyncTask(mSiteAlertWebView).execute(new Void[0]);
        AdColony.resume(this);
    }

    @Override // com.roblox.client.RobloxActivity, android.app.Activity
    protected void onPause() {
        super.onPause();
        AdColony.pause();
    }

    @Override // com.roblox.client.RobloxActivity
    public void onUserInfoChanged(boolean trackScreen) {
        if (this.mLoggedIn) {
            this.mUsernameEditText.setText(this.mUsername);
            if (this.mThumbnailUrl != null && !this.mThumbnailUrl.equals("")) {
                new ImageViewHttpLoader((ImageView) findViewById(R.id.thumbnail_url), null).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, this.mThumbnailUrl);
            }
            this.mUsernameTextView.setText(this.mUsername);
            this.mRobuxBalanceTextView.setText(" " + this.mRobuxBalance);
            this.mTicketsTextView.setText(" " + this.mTicketsBalance);
            this.mSidebarViewFlipper.setDisplayedChild(1);
            this.mViewFlipper.setDisplayedChild(1);
            if (trackScreen) {
                Utils.sendAnalyticsScreen(this, "LoggedInScreen");
            }
        } else {
            this.mSidebarViewFlipper.setDisplayedChild(0);
            if (trackScreen) {
                Utils.sendAnalyticsScreen(this, "LogInScreen");
            }
        }
        hideKeyboard();
    }

    public void hideKeyboard() {
        this.mFocusableView.requestFocus();
        InputMethodManager inputManager = (InputMethodManager) getSystemService("input_method");
        inputManager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 2);
    }

    @Override // com.roblox.client.FragmentSignUp.SignUpCallbacks
    public void onFragmentSignUpCancel() {
        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.remove(this.mFragmentSignUp);
        ft.commit();
        fm.executePendingTransactions();
    }

    @Override // com.roblox.client.FragmentSignUp.SignUpCallbacks
    public void onFragmentSignUpOk(JSONObject response) {
        onFragmentSignUpCancel();
        onLogin(response, true);
    }

    private void propigateLoginKeyValues() {
        this.mUsername = this.mUsernameEditText.getText().toString();
        this.mPassword = this.mPasswordEditText.getText().toString();
        this.mRememberPassword = this.mRememberPasswordCheckBox.isChecked();
        writeLoginKeyValues();
    }

    @Override // com.roblox.client.RobloxActivity
    protected void onLogout() {
        super.onLogout();
        Utils.sendAnalytics(this, "ActivityNativeMain", "logout");
        this.mPasswordEditText.setText("");
        this.mViewFlipper.setDisplayedChild(0);
        this.mSidebarViewFlipper.setDisplayedChild(0);
        mSiteAlertWebView.setVisibility(8);
    }

    public void onNop(View view) {
    }

    public void onPlayNow(View view) {
        if (this.mLoggedIn) {
            onLogout();
        }
        propigateLoginKeyValues();
        if (!this.mUsername.isEmpty() && !this.mPassword.isEmpty()) {
            new RobloxActivity.LoginAsyncTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
            return;
        }
        this.mSidebarViewFlipper.setDisplayedChild(0);
        this.mViewFlipper.setDisplayedChild(1);
        hideKeyboard();
        new SiteAlertAsyncTask(mSiteAlertWebView).execute(new Void[0]);
        Utils.sendAnalyticsScreen(this, "PlayNowScreen");
    }

    public void onRememberPassword(View view) {
        propigateLoginKeyValues();
    }

    public void onExecuteLogin(View ignored) {
        propigateLoginKeyValues();
        if (this.mUsername.isEmpty()) {
            Utils.alertExclusively(this, R.string.NoSignupUsernameError);
        } else if (this.mPassword.isEmpty()) {
            Utils.alertExclusively(this, R.string.NoSignupPasswordError);
        } else {
            new RobloxActivity.LoginAsyncTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
            new SiteAlertAsyncTask(mSiteAlertWebView).execute(new Void[0]);
        }
    }

    public void onSignUp(View view) {
        propigateLoginKeyValues();
        FragmentSignUp f = new FragmentSignUp();
        f.setSignUpCallbacks(this);
        this.mFragmentSignUp = f;
        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.activity_root, f);
        ft.addToBackStack(null);
        ft.commit();
        fm.executePendingTransactions();
        Utils.sendAnalytics(this, "ActivityNativeMain", "signup");
        Utils.sendAnalyticsScreen(this, "SignUpScreen");
    }

    public void onAbout(View view) {
        FragmentAbout f = new FragmentAbout();
        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.activity_root, f);
        ft.addToBackStack(null);
        ft.commit();
        Utils.sendAnalytics(this, "ActivityNativeMain", "about");
    }

    private String getUrl(View view) {
        switch (view.getId()) {
            case R.id.games /* 2131296293 */:
                return RobloxSettings.gamesUrl();
            case R.id.native_main_resize_text_1 /* 2131296294 */:
            case R.id.native_main_resize_text_2 /* 2131296296 */:
            case R.id.native_main_resize_text_3 /* 2131296299 */:
            case R.id.native_main_resize_text_4 /* 2131296301 */:
            case R.id.buttonLaunchPlace /* 2131296302 */:
            case R.id.editTextPlaceID /* 2131296303 */:
            case R.id.native_main_resize_text_5 /* 2131296305 */:
            default:
                return null;
            case R.id.builders_club /* 2131296295 */:
                return RobloxSettings.buildersClubUrl();
            case R.id.groups /* 2131296297 */:
                return RobloxSettings.groupsUrl();
            case R.id.forums:
                return RobloxSettings.forumsUrl();
            case R.id.catalog /* 2131296298 */:
                return RobloxSettings.catalogUrl();
            case R.id.profile /* 2131296300 */:
                return RobloxSettings.profileUrl();
            case R.id.inventory /* 2131296304 */:
                return RobloxSettings.inventoryUrl();
            case R.id.messages /* 2131296306 */:
                return RobloxSettings.messagesUrl();
        }
    }

    public void onMainImageButton(View view) {
        String url = getUrl(view);
        if (url == null) {
            Utils.alertUnexpectedError(this, "Unimplemented");
            return;
        }
        if (!this.mLoggedIn && view.getId() != R.id.games) {
            Utils.sendAnalytics(this, "MainImageButtonNotLoggedIn", url);
            Utils.alertExclusively(this, R.string.YouMustLogin);
            return;
        }
        Utils.sendAnalytics(this, "ActivityNativeMain", url);
        Intent intent = new Intent(this, (Class<?>) ActivityWebView.class);
        intent.putExtra("roblox_url", url);
        intent.putExtra("roblox_back_activity", "ActivityNativeMain");
        startActivity(intent);
        finish();
    }

    private void initializeMAT() {
        MobileAppTracker.init(getApplicationContext(), "18714", "4316dbf38e776530b30b954d3786bd41");
        this.mobileAppTracker = MobileAppTracker.getInstance();
        File googleAnalyticsTrace = new File(getApplicationContext().getFilesDir() + "/gaClientId");
        int lastVersionCode = this.mKeyValues.getInt("last_version_code", 0);
        boolean isExistingUser = googleAnalyticsTrace.exists() || lastVersionCode != 0;
        if (isExistingUser) {
            this.mobileAppTracker.setExistingUser(true);
        }
        new Thread(new Runnable() { // from class: com.roblox.client.ActivityNativeMain.9
            @Override // java.lang.Runnable
            public void run() {
                try {
                    AdvertisingIdClient.Info adInfo = AdvertisingIdClient.getAdvertisingIdInfo(ActivityNativeMain.this.getApplicationContext());
                    ActivityNativeMain.this.mobileAppTracker.setGoogleAdvertisingId(adInfo.getId(), adInfo.isLimitAdTrackingEnabled());
                } catch (GooglePlayServicesNotAvailableException e) {
                    ActivityNativeMain.this.mobileAppTracker.setAndroidId(Settings.Secure.getString(ActivityNativeMain.this.getContentResolver(), "android_id"));
                } catch (GooglePlayServicesRepairableException e2) {
                    ActivityNativeMain.this.mobileAppTracker.setAndroidId(Settings.Secure.getString(ActivityNativeMain.this.getContentResolver(), "android_id"));
                } catch (IOException e3) {
                    ActivityNativeMain.this.mobileAppTracker.setAndroidId(Settings.Secure.getString(ActivityNativeMain.this.getContentResolver(), "android_id"));
                } catch (NullPointerException e4) {
                    ActivityNativeMain.this.mobileAppTracker.setAndroidId(Settings.Secure.getString(ActivityNativeMain.this.getContentResolver(), "android_id"));
                }
            }
        }).start();
    }

    private void initializeAdColony() {
        String versionName = "";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
        }
        String options = "version:" + versionName + ",store:google";
        AdColony.configure(this, options, "appe0b7456ae2e249ed91", "vz80b7495faabe44d4b2");
    }
}
