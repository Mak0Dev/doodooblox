package com.roblox.client;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import com.google.android.gms.analytics.GoogleAnalytics;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.fmod.FMOD;

/* JADX INFO: loaded from: classes.dex */
public class ActivityGlView extends RobloxServiceActivity implements SurfaceHolder.Callback {
    public static final boolean ALLOW_SAME_PROCESS_DEBUGGING = false;
    private static final int SHOW_VIDEO_AD_REQUEST = 1;
    public static final int SURFACE_CREATED = 2;
    public static final int SURFACE_DESTROYED = 4;
    public static final int SURFACE_INVALID = -1;
    public static final int SURFACE_NOT_READY = 0;
    public static final int SURFACE_READY_NOT_CREATED = 1;
    public static final int SURFACE_READY_TO_DESTROY = 3;
    private static final String TAG = "ActivityGlView";
    private static ActivityGlView mSingleton = null;
    private RbxKeyboard mGlEditTextView = null;
    private SurfaceView mSurfaceView = null;
    private boolean mDifferentProcess = false;
    private Handler mUIThreadHandler = null;
    private long mCurrentTextBox = 0;
    private long mMTBFStartTimeMilliseconds = 0;
    private boolean mAlreadyCreated = false;
    private boolean mAlreadyDestroyed = false;
    private boolean mAllowPauseResume = false;
    private boolean mPausedForDialog = false;
    private DeviceBlacklistChecker mDeviceBlacklistChecker = null;
    private InputListener mInputListener = null;
    private int mSurfaceState = -1;
    private boolean mStartRequested = false;
    private int mSurfaceWidth = 0;
    private int mSurfaceHeight = 0;

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativeCallMessagesFromMainThread();

    private static native void nativeHandleBackPressed();

    private static native void nativeInGamePurchaseFinished(boolean z, long j, String str);

    private static native void nativeOnLowMemory();

    // DoodooBlox: signatures below match the engine built from source (Android/libroblox/JNIGLActivity.cpp).
    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativePassText(long textBoxInFocus, String text, boolean enterPressed, int cursorPosition);

    private static native void nativeReleaseFocus(long j);

    private static native void nativeShutDownGraphics(Surface surface);

    private static native void nativeStartGame(Surface surface, int placeId, int userId, String accessCode, String gameId, int joinRequestType,
            String assetPath, float density, boolean isTouchDevice, String osVersion, String deviceName, String appVersion, String model);

    private static native void nativeStartUpGraphics(Surface surface, float density, String model);

    private static native void nativeStopGame();

    private static native void nativeVideoAdFinished(boolean z);

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "ActivityGlView onCreate()");
        Intent incomingIntent = getIntent();
        if (incomingIntent == null) {
            Log.e(TAG, "Launching GL Activity without Intent.");
            finish();
            return;
        }
        int launcherPid = incomingIntent.getIntExtra("roblox_launcher_pid", -1);
        boolean launcherDebuggerAttached = incomingIntent.getBooleanExtra("roblox_launcher_debugger_attached", false);
        int myPid = Process.myPid();
        this.mDifferentProcess = myPid != launcherPid;
        Object[] objArr = new Object[2];
        objArr[0] = Integer.valueOf(launcherPid);
        objArr[1] = launcherDebuggerAttached ? "attached" : "none";
        String msg = Utils.format("Incoming Intent ActivityGlView Pid:%d Debuger:%s", objArr);
        Log.i(TAG, msg);
        if (launcherDebuggerAttached && this.mDifferentProcess) {
            Log.w(TAG, "ActivityGlView waiting for debugger");
            Debug.waitForDebugger();
        }
        if (mSingleton != null) {
            Log.e(TAG, "*** Trying to Create twice. ***");
            finish();
            return;
        }
        mSingleton = this;
        if (!this.mDifferentProcess) {
            Log.e(TAG, "ActivityGlView launched in same process.");
            finish();
            return;
        }
        this.mUIThreadHandler = new Handler(Looper.getMainLooper());
        doCrashGuardSetup();
        String reason = RobloxSettings.deviceNotSupportedString();
        if (reason != null) {
            Utils.alertExclusivelyFormatted(this, R.string.UnsupportedDeviceSkippable, reason);
        } else {
            this.mDeviceBlacklistChecker = new DeviceBlacklistChecker(Build.MODEL, RobloxSettings.blacklistUrl(), mSingleton);
            this.mDeviceBlacklistChecker.execute(new Void[0]);
        }
        try {
            System.loadLibrary("fmod");
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, e.getLocalizedMessage());
            System.loadLibrary("fmodL");
        }
        System.loadLibrary("roblox");
        RobloxApplication app = (RobloxApplication) getApplication();
        if (app.checkShowCriticalError()) {
            Log.e(TAG, "Trying to create GLView after critcal error.");
            return;
        }
        enterFullscreen();
        setContentView(R.layout.activity_glview);
        HttpAgent.onCreate(this);
        RobloxSettings.updateNativeSettings();
        this.mGlEditTextView = (RbxKeyboard) findViewById(R.id.gl_edit_text);
        initGlEditTextView();
        this.mSurfaceView = (SurfaceView) findViewById(R.id.surfaceview);
        initSurfaceView();
        this.mSurfaceState = 0;
        FMOD.init(this);
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        if (this.mAlreadyDestroyed) {
            Log.e(TAG, "*** Trying to Destroy twice. ***");
            return;
        }
        this.mAlreadyDestroyed = true;
        doNotifyService(2, 0);
        doCrashGuardTeardown(true);
        if (this.mSurfaceView != null) {
            FMOD.close(); // only initialized once onCreate got that far
        }
        GoogleAnalytics.getInstance(getBaseContext()).dispatchLocalHits();
        if (this.mDifferentProcess) {
            System.exit(0);
        } else {
            super.onDestroy();
        }
    }

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart");
        RobloxSettings.enableNDKProfiler(true);
    }

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onStop() {
        super.onStop();
        RobloxSettings.enableNDKProfiler(false);
        if (this.mSurfaceState < 2) {
            this.mSurfaceState = 4;
        } else if (this.mSurfaceState == 2 && !allowPauseResume()) {
            this.mSurfaceState = 3;
        }
        if (this.mInputListener != null) {
            this.mInputListener.stopSensorListening();
        }
    }

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onResume() {
        super.onResume();
        Utils.sendAnalyticsScreen(this, TAG);
        HttpAgent.onResume();
        if (!allowPauseResume() && this.mSurfaceState >= 3) {
            this.mUIThreadHandler.postDelayed(new Runnable() { // from class: com.roblox.client.ActivityGlView.1
                @Override // java.lang.Runnable
                public void run() {
                    ActivityGlView.exitGame();
                }
            }, 50L);
        }
        if (this.mInputListener != null) {
            this.mInputListener.startSensorListening(false);
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
        HttpAgent.onPause(getCacheDir(), null);
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        nativeHandleBackPressed();
    }

    @Override // android.app.Activity
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterFullscreen();
        }
    }

    // DoodooBlox: fill the whole screen on modern phones - hide the nav bar
    // (immersive sticky) and draw under the camera cutout.
    private void enterFullscreen() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WindowManager.LayoutParams lp = window.getAttributes();
                // WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES (API 28)
                WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(lp, 1);
                window.setAttributes(lp);
            } catch (Exception e) {
                Log.w(TAG, "layoutInDisplayCutoutMode not available: " + e);
            }
        }
        if (Build.VERSION.SDK_INT >= 19) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override // android.app.Activity, android.content.ComponentCallbacks
    public void onLowMemory() {
        super.onLowMemory();
        if (this.mSurfaceState == 2) {
            nativeOnLowMemory();
        }
    }

    @Override // android.app.Activity
    protected void onNewIntent(Intent intent) {
        Log.w(TAG, "onNewIntent called");
    }

    @Override // com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) {
            nativeVideoAdFinished(data.getBooleanExtra("shown", false));
        }
    }

    protected boolean allowPauseResume() {
        return this.mAllowPauseResume || this.mPausedForDialog;
    }

    private void initGlEditTextView() {
        this.mGlEditTextView.setVisibility(8);
        this.mGlEditTextView.setImeOptions(2);
        this.mGlEditTextView.setSingleLine(true);
        this.mGlEditTextView.setText("");
        this.mGlEditTextView.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.roblox.client.ActivityGlView.2
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // "Go"/"Done" from the soft keyboard, or Enter from a hardware keyboard (an emulator's
                // PC keyboard sends IME_NULL with the key event, which used to leave chat unsent)
                boolean hardwareEnter = actionId == 0 && event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId != 2 && actionId != 6 && !hardwareEnter) {
                    return false;
                }
                String text = v.getText().toString();
                if (ActivityGlView.this.mSurfaceState == 2) {
                    ActivityGlView.nativePassText(ActivityGlView.this.mCurrentTextBox, text, true, text.length());
                } else {
                    Log.w(ActivityGlView.TAG, "nativePassText not ready");
                }
                ActivityGlView.this.mGlEditTextView.setCurrentTextBox(0L);
                v.setVisibility(8);
                InputMethodManager imm = (InputMethodManager) ActivityGlView.this.mSurfaceView.getContext().getSystemService("input_method");
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
        });
    }

    public static int getSurfaceState() {
        return mSingleton.mSurfaceState;
    }

    private void initSurfaceView() {
        this.mSurfaceView.setFocusable(true);
        this.mSurfaceView.setFocusableInTouchMode(true);
        this.mSurfaceView.getHolder().addCallback(this);
    }

    // DoodooBlox: the engine reads the window size only when graphics start, so the
    // game is started from here once the surface has its final landscape size
    // (surfaceCreated can fire while still in portrait or with the nav bar visible,
    // which left the game squashed or letterboxed on modern screens).
    private final Runnable mStartAnyway = new Runnable() {
        @Override // java.lang.Runnable
        public void run() {
            if (ActivityGlView.this.mSurfaceState == 1 && !ActivityGlView.this.mStartRequested) {
                Log.w(ActivityGlView.TAG, "Surface never went landscape, starting game anyway");
                ActivityGlView.this.requestStartGame();
            }
        }
    };

    private void requestStartGame() {
        this.mUIThreadHandler.removeCallbacks(this.mStartAnyway);
        this.mStartRequested = true;
        doNotifyService(4, 0);
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.i(TAG, "surfaceChanged " + w + "x" + h);
        if (w <= 0 || h <= 0 || h > w) {
            // transient portrait size while rotating to landscape
            if (this.mSurfaceState == 1 && !this.mStartRequested) {
                this.mUIThreadHandler.removeCallbacks(this.mStartAnyway);
                this.mUIThreadHandler.postDelayed(this.mStartAnyway, 2000L);
            }
            return;
        }
        if (this.mSurfaceState == 1 && !this.mStartRequested) {
            this.mSurfaceWidth = w;
            this.mSurfaceHeight = h;
            requestStartGame();
        } else if (this.mSurfaceState == 2 && (w != this.mSurfaceWidth || h != this.mSurfaceHeight)) {
            Log.w(TAG, "*** surface resized " + this.mSurfaceWidth + "x" + this.mSurfaceHeight + " -> " + w + "x" + h + ", restarting graphics ***");
            this.mSurfaceWidth = w;
            this.mSurfaceHeight = h;
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            nativeShutDownGraphics(holder.getSurface());
            nativeStartUpGraphics(holder.getSurface(), metrics.density, Build.MODEL);
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder holder) {
        if (this.mSurfaceState == 0) {
            this.mSurfaceState = 1;
            // game start is requested from surfaceChanged, which always follows
        } else if (allowPauseResume() && this.mSurfaceState == 2) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            Log.w(TAG, "*** nativeStartUpGraphics ***");
            Surface surface = this.mSurfaceView.getHolder().getSurface();
            nativeStartUpGraphics(surface, metrics.density, Build.MODEL);
        }
        this.mPausedForDialog = false;
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        RobloxApplication app = (RobloxApplication) getApplication();
        if (app.checkShowCriticalError()) {
            Log.e(TAG, "Trying to shut down GL surface after critcal error.");
            return;
        }
        if (this.mSurfaceState == 2 || this.mSurfaceState == 3) {
            if (allowPauseResume()) {
                Log.w(TAG, "*** nativeShutDownGraphics ***");
                nativeShutDownGraphics(this.mSurfaceView.getHolder().getSurface());
            } else {
                Log.i(TAG, ">>> Calling nativeStopGame");
                nativeStopGame();
                Log.i(TAG, "<<< Returned from nativeStopGame");
                this.mSurfaceState = 4;
            }
        }
    }

    @Override // com.roblox.client.RobloxServiceActivity
    protected void onServiceNotification(int notification, int extra) {
        if (notification == 3) {
            if (extra == -1) {
                exitGame();
                return;
            }
            if (this.mSurfaceState != 1) {
                Log.w(TAG, "surfaceCreated->onServiceNotification unexpectedly: " + this.mSurfaceState);
                return;
            }
            this.mSurfaceState = 2;
            RobloxApplication app = (RobloxApplication) getApplication();
            if (app.checkShowCriticalError()) {
                Log.e(TAG, "Trying to create GL surface after critcal error.");
                return;
            }
            int placeId = extra;
            Log.i(TAG, "surfaceCreated->onServiceNotification: Place ID: " + placeId);
            XAPKManager.unpackAssetsAsync(this);
            String assetPath = XAPKManager.unpackAssets(this);
            Surface surface = this.mSurfaceView.getHolder().getSurface();
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            // join request type 0 = plain place join (JOIN_GAME_REQUEST_PLACEID)
            nativeStartGame(surface, placeId, 0, "", "", 0, assetPath, metrics.density,
                    getPackageManager().hasSystemFeature("android.hardware.touchscreen"), String.valueOf(Build.VERSION.SDK_INT),
                    Build.MANUFACTURER + " " + Build.MODEL, RobloxSettings.version(), Build.MODEL);
            this.mInputListener = new InputListener(this, this.mSurfaceView);
            this.mSurfaceView.setOnTouchListener(this.mInputListener);
        }
    }

    private void doCrashGuardSetup() {
        Utils.doCrashGuardCheck(this, false);
        this.mMTBFStartTimeMilliseconds = System.currentTimeMillis();
        doCrashGuardUpdate(true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doCrashGuardUpdate(boolean firstTime) {
        if (this.mMTBFStartTimeMilliseconds != 0) {
            long endTimeMilliseconds = System.currentTimeMillis();
            long milliseconds = endTimeMilliseconds - this.mMTBFStartTimeMilliseconds;
            if (milliseconds < 1) {
                milliseconds = 1;
            }
            char[] millisecondsChar = Long.toString(milliseconds).toCharArray();
            try {
                FileOutputStream fos = openFileOutput(Utils.CRASH_GUARD, 0);
                for (char c : millisecondsChar) {
                    fos.write((byte) c);
                }
                fos.flush();
                fos.getFD().sync();
                fos.close();
                if (firstTime) {
                    Utils.sendAnalytics(this, Utils.CRASH_GUARD_CHANNEL, "CreateOK");
                }
                this.mUIThreadHandler.postDelayed(new Runnable() { // from class: com.roblox.client.ActivityGlView.3
                    @Override // java.lang.Runnable
                    public void run() {
                        ActivityGlView.this.doCrashGuardUpdate(false);
                    }
                }, 1000L);
            } catch (IOException e) {
                Utils.sendAnalytics(this, Utils.CRASH_GUARD_CHANNEL, "FailedCreate");
            }
        }
    }

    private void doCrashGuardTeardown(boolean onDestroy) {
        try {
            FileOutputStream fos = openFileOutput(Utils.CRASH_GUARD_OK, 0);
            fos.write(120);
            fos.flush();
            fos.getFD().sync();
            fos.close();
        } catch (IOException e) {
        }
        this.mMTBFStartTimeMilliseconds = 0L;
    }

    public static void releaseFocus(long textBoxInFocus) {
        if (mSingleton.mSurfaceState != 2) {
            Log.w(TAG, "releaseFocus() called unexpectedly: " + mSingleton.mSurfaceState);
        } else {
            nativeReleaseFocus(textBoxInFocus);
        }
    }

    public static void sendAppEvent(final boolean blockUntilSent) throws InterruptedException {
        final CountDownLatch appEventSentSignal = blockUntilSent ? new CountDownLatch(1) : null;
        mSingleton.mUIThreadHandler.postAtFrontOfQueue(new Runnable() { // from class: com.roblox.client.ActivityGlView.4
            @Override // java.lang.Runnable
            public void run() {
                ActivityGlView.nativeCallMessagesFromMainThread();
                if (blockUntilSent) {
                    appEventSentSignal.countDown();
                }
            }
        });
        if (blockUntilSent) {
            appEventSentSignal.await();
        }
    }

    public static void postAppEvent() {
        mSingleton.mUIThreadHandler.postAtFrontOfQueue(new Runnable() { // from class: com.roblox.client.ActivityGlView.5
            @Override // java.lang.Runnable
            public void run() {
                ActivityGlView.nativeCallMessagesFromMainThread();
            }
        });
    }

    public static class showEditText implements Runnable {
        String param;

        public showEditText(String parameter) {
            this.param = parameter;
        }

        @Override // java.lang.Runnable
        public void run() {
            ActivityGlView.mSingleton.mGlEditTextView.setVisibility(0);
            CharSequence charText = this.param;
            ActivityGlView.mSingleton.mGlEditTextView.setText(charText);
            ActivityGlView.mSingleton.mGlEditTextView.requestFocus();
            InputMethodManager imm = (InputMethodManager) ActivityGlView.mSingleton.mSurfaceView.getContext().getSystemService("input_method");
            imm.showSoftInput(ActivityGlView.mSingleton.mGlEditTextView, 1);
        }
    }

    public static void showKeyboard(long textBox, String textFromTextBox) {
        mSingleton.mCurrentTextBox = textBox;
        mSingleton.mGlEditTextView.setCurrentTextBox(textBox);
        Runnable showKeyBoardRunnable = new showEditText(textFromTextBox);
        mSingleton.mUIThreadHandler.post(showKeyBoardRunnable);
    }

    public static void listenToMotionEvents(String motionType) {
        mSingleton.mInputListener.startSensorListening(true);
    }

    public static void showVideoAd() {
        Intent intent = new Intent(mSingleton, (Class<?>) ActivityAdColony.class);
        mSingleton.mPausedForDialog = true;
        mSingleton.startActivityForResult(intent, 1);
    }

    public static void promptNativePurchase(long player, String productId, String username) {
        mSingleton.mStoreMgr.doInAppPurchaseForProduct(mSingleton, productId, username, player);
    }

    public static void inGamePurchaseFinished(boolean success, long player, String productId) {
        if (mSingleton != null && mSingleton.mSurfaceState == 2) {
            nativeInGamePurchaseFinished(success, player, productId);
        }
    }

    public static void exitGame() {
        Intent intent = new Intent(mSingleton, (Class<?>) ActivityWebView.class);
        intent.putExtra("roblox_back", true);
        mSingleton.startActivity(intent);
        mSingleton.finish();
    }

    // DoodooBlox: callbacks the source engine looks up in JNIMain.cpp; the 2014 shell lacked these.

    public static void hideKeyboard() {
        mSingleton.mCurrentTextBox = 0L;
        mSingleton.mGlEditTextView.setCurrentTextBox(0L);
        mSingleton.mUIThreadHandler.post(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                InputMethodManager imm = (InputMethodManager) ActivityGlView.mSingleton.mSurfaceView.getContext().getSystemService("input_method");
                imm.hideSoftInputFromWindow(ActivityGlView.mSingleton.mGlEditTextView.getWindowToken(), 0);
                ActivityGlView.mSingleton.mGlEditTextView.setVisibility(8);
            }
        });
    }

    public static void showAdColonyAd() {
        showVideoAd();
    }

    public static void showGoogleAd(String url) {
        Log.w(TAG, "showGoogleAd not supported, skipping ad");
        mSingleton.mUIThreadHandler.post(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                if (ActivityGlView.mSingleton.mSurfaceState == 2) {
                    ActivityGlView.nativeVideoAdFinished(false);
                }
            }
        });
    }

    public static void exitGameWithError(final String errorResId) {
        mSingleton.mUIThreadHandler.post(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                // the engine passes a strings.xml name; fall back to the raw name if this build lacks it
                int androidId = ActivityGlView.mSingleton.getResources().getIdentifier(errorResId, "string", ActivityGlView.mSingleton.getPackageName());
                String message = androidId != 0 ? ActivityGlView.mSingleton.getString(androidId) : errorResId;
                new AlertDialog.Builder(ActivityGlView.mSingleton).setMessage(message)
                        .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                            @Override // android.content.DialogInterface.OnClickListener
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                ActivityGlView.exitGame();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override // android.content.DialogInterface.OnCancelListener
                            public void onCancel(DialogInterface dialog) {
                                dialog.dismiss();
                                ActivityGlView.exitGame();
                            }
                        }).create().show();
            }
        });
    }

    public static String getApiUrl() {
        return RobloxSettings.baseUrlSecureWWW();
    }
}
