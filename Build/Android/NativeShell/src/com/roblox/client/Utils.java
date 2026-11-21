package com.roblox.client;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.TextView;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class Utils {
    public static final String CRASH_GUARD = "crash_guard";
    public static final String CRASH_GUARD_CHANNEL = "MTBF_CRASH_GUARD_3";
    public static final String CRASH_GUARD_OK = "crash_guard_ok";
    private static final String TAG = "roblox.utils";
    private static AlertDialog mExclusiveDialog = null;
    private static int mExclusiveDialogContext = 0;
    private static RateMeMaybe mRateMeMaybe = null;

    public static int getDeviceTotalMegabytes() {
        String s = null;
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile("/proc/meminfo", "r");
            s = reader.readLine();
        } catch (IOException e) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                }
            }
        }
        if (s == null) {
            return 0;
        }
        String[] tokens = s.split("\\s+");
        if (tokens.length < 2) {
            return 0;
        }
        long mb = 0;
        try {
            long kb = Long.parseLong(tokens[1]);
            mb = kb / 1024;
        } catch (NumberFormatException e) {
        }
        return (int) mb;
    }

    public static boolean getDeviceHasNEON() {
        List<String> lines = readTextFile("/proc/cpuinfo");
        for (String line : lines) {
            if (line.contains("neon")) {
                return true;
            }
        }
        return false;
    }

    public static Point getScreenSize(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService("window");
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        return size;
    }

    public static Point getScreenDpi(Context c) {
        DisplayMetrics metrics = c.getResources().getDisplayMetrics();
        Point dpi = new Point();
        dpi.x = (int) metrics.xdpi;
        dpi.y = (int) metrics.ydpi;
        return dpi;
    }

    public static Point getScreenDpSize(Context c) {
        DisplayMetrics metrics = c.getResources().getDisplayMetrics();
        Point dp = new Point();
        dp.x = (int) (metrics.widthPixels / metrics.density);
        dp.y = (int) (metrics.heightPixels / metrics.density);
        return dp;
    }

    public static String getDeviceName() {
        String name;
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.startsWith(manufacturer)) {
            name = model;
        } else {
            name = manufacturer + " " + model;
        }
        char capital = Character.toUpperCase(name.charAt(0));
        return capital + name.substring(1, name.length());
    }

    public static String getWebkitVersion(Context c) {
        String userAgent = new WebView(c).getSettings().getUserAgentString();
        String[] tokens = userAgent.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].startsWith("AppleWebKit")) {
                return tokens[i];
            }
        }
        return "AppleWebKit/Unknown";
    }

    public static void createDirectory(File path) {
        path.mkdirs();
    }

    public static void writeToFile(File path, String data) {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(path));
            writer.write(data);
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                }
            }
        }
    }

    static List<String> readTextFile(String path) {
        List<String> strings = new ArrayList<>();
        File file = new File(path);
        try {
            Scanner scanner = new Scanner(file);
            while (scanner.hasNextLine()) {
                try {
                    strings.add(scanner.nextLine());
                } catch (Throwable th) {
                    scanner.close();
                    throw th;
                }
            }
            scanner.close();
        } catch (FileNotFoundException e) {
        }
        return strings;
    }

    static boolean checkForRawResource(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "raw", context.getPackageName());
        return id != 0;
    }

    static JSONObject loadJson(Context context, int res) throws IOException {
        InputStream is = context.getResources().openRawResource(res);
        Writer writer = new StringWriter();
        char[] buffer = new char[1024];
        try {
            try {
                Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                while (true) {
                    int n = reader.read(buffer);
                    if (n == -1) {
                        break;
                    }
                    writer.write(buffer, 0, n);
                }
                String jsonString = writer.toString();
                JSONObject json = new JSONObject(jsonString);
                try {
                    is.close();
                } catch (IOException e) {
                }
                return json;
            } catch (Throwable th) {
                try {
                    is.close();
                } catch (IOException e2) {
                }
                throw th;
            }
        } catch (IOException e3) {
            Log.e(TAG, "IOException loading JSON.");
            throw new IOException("IOException loading JSON resource # " + res);
        } catch (JSONException e4) {
            Log.e(TAG, "Cannot parse JSON.");
            throw new IOException("Cannot parse JSON resource # " + res);
        }
    }

    public static void killBackgroundProcesses(Context c) {
        String packageName = c.getPackageName();
        ActivityManager activityManager = (ActivityManager) c.getSystemService("activity");
        activityManager.killBackgroundProcesses(packageName);
    }

    public static String format(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    public static boolean isNetworkConnected(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService("connectivity");
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null;
    }

    private static void setExclusiveDialog(Context c, AlertDialog alertDialog) {
        mExclusiveDialog = alertDialog;
        mExclusiveDialogContext = System.identityHashCode(c);
        mExclusiveDialog.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.roblox.client.Utils.1
            @Override // android.content.DialogInterface.OnDismissListener
            public void onDismiss(DialogInterface arg0) {
                AlertDialog unused = Utils.mExclusiveDialog = null;
                int unused2 = Utils.mExclusiveDialogContext = 0;
            }
        });
    }

    public static void contextSwitchExclusiveDialog(Context newContext) {
        try {
            int id = System.identityHashCode(newContext);
            if (mExclusiveDialog != null && mExclusiveDialogContext == id) {
                mExclusiveDialog.cancel();
                mExclusiveDialog = null;
            }
        } catch (IllegalArgumentException e) {
        }
    }

    protected static AlertDialog alert(Context c, int stringResource) {
        String s = c.getResources().getString(stringResource);
        return alert(c, s);
    }

    protected static AlertDialog alert(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextAppearance(c, android.R.attr.textAppearanceLarge);
        t.setTextSize(20.0f);
        t.setEllipsize(null);
        AlertDialog alertDialog = new AlertDialog.Builder(c).create();
        alertDialog.setView(t, 150, 100, 150, 100);
        alertDialog.setCanceledOnTouchOutside(true);
        try {
            alertDialog.show();
        } catch (WindowManager.BadTokenException e) {
        }
        return alertDialog;
    }

    protected static AlertDialog alertFormatted(Context c, int stringResource, Object... args) {
        String format = c.getResources().getString(stringResource);
        String s = String.format(Locale.ROOT, format, args);
        return alert(c, s);
    }

    public static synchronized AlertDialog alertExclusively(Context c, int stringResource) {
        AlertDialog alertDialog;
        if (mExclusiveDialog == null) {
            setExclusiveDialog(c, alert(c, stringResource));
            alertDialog = mExclusiveDialog;
        } else {
            alertDialog = null;
        }
        return alertDialog;
    }

    public static synchronized AlertDialog alertExclusively(Context c, String s) {
        AlertDialog alertDialog;
        if (mExclusiveDialog == null) {
            setExclusiveDialog(c, alert(c, s));
            alertDialog = mExclusiveDialog;
        } else {
            alertDialog = null;
        }
        return alertDialog;
    }

    public static synchronized AlertDialog alertExclusivelyFormatted(Context c, int stringResource, Object... args) {
        AlertDialog alertDialog;
        if (mExclusiveDialog == null) {
            setExclusiveDialog(c, alertFormatted(c, stringResource, args));
            alertDialog = mExclusiveDialog;
        } else {
            alertDialog = null;
        }
        return alertDialog;
    }

    public static synchronized AlertDialog alertUnexpectedError(RobloxServiceActivity a, String err) {
        sendAnalytics(a, "UnexpectedError", err);
        return alertFormatted(a, R.string.UnexpectedError, err);
    }

    public static synchronized void sendUnexpectedError(RobloxServiceActivity a, String err) {
        sendAnalytics(a, "UnexpectedErrorSilent", err);
    }

    public static boolean alertIfNetworkNotConnected(Context c) {
        boolean isNotConnected = !isNetworkConnected(c);
        if (isNotConnected) {
            alertExclusively(c, R.string.ConnectionError);
        }
        return isNotConnected;
    }

    public static void sendAnalytics(RobloxServiceActivity a, String category, String action) {
        if (a.isGooglePlayServicesAvailable()) {
            Tracker t = ((RobloxApplication) a.getApplication()).getAndroidTracker();
            t.send(new HitBuilders.EventBuilder().setCategory(category).setAction(action).build());
            Log.i(TAG, format("sendAnalytics: %s %s", category, action));
        }
    }

    public static void sendTiming(RobloxServiceActivity a, String category, String action, long milliseconds) {
        if (a.isGooglePlayServicesAvailable()) {
            Tracker t = ((RobloxApplication) a.getApplication()).getAndroidTracker();
            HitBuilders.EventBuilder action2 = new HitBuilders.EventBuilder().setCategory(category).setAction(action);
            action2.setValue(milliseconds / 1000);
            t.send(action2.build());
            Log.i(TAG, format("sendTiming: %s %s %d", category, action, Long.valueOf(milliseconds / 1000)));
        }
    }

    public static void sendAnalyticsScreen(RobloxServiceActivity a, String category) {
        if (a.isGooglePlayServicesAvailable()) {
            Tracker t = ((RobloxApplication) a.getApplication()).getAndroidTracker();
            t.setScreenName(category);
            t.send(new HitBuilders.EventBuilder().setCategory(category).setAction("ScreenView").build());
        }
    }

    public static void onRateMeMaybe(Activity a) {
        if (mRateMeMaybe == null) {
            RateMeMaybe.resetData(a);
            mRateMeMaybe = new RateMeMaybe(a);
        }
        mRateMeMaybe.setPromptMinimums(10, 1, 10, 2);
        mRateMeMaybe.setHandleCancelAsNeutral(false);
        String rateMeDialogTitle = a.getResources().getString(R.string.RateMeDialogTitle);
        String rateMeDialogMessage = a.getResources().getString(R.string.RateMeDialogMessage);
        String rateMeRateIt = a.getResources().getString(R.string.RateMeRateIt);
        String rateMeNotNow = a.getResources().getString(R.string.RateMeNotNow);
        String rateMeNever = a.getResources().getString(R.string.RateMeNever);
        mRateMeMaybe.setDialogTitle(rateMeDialogTitle);
        mRateMeMaybe.setDialogMessage(rateMeDialogMessage);
        mRateMeMaybe.setPositiveBtn(rateMeRateIt);
        mRateMeMaybe.setNeutralBtn(rateMeNotNow);
        mRateMeMaybe.setNegativeBtn(rateMeNever);
        mRateMeMaybe.run();
    }

    public static void chmod(String path, int mode) {
        try {
            File f = new File(path);
            Method setPermissions = Class.forName("android.os.FileUtils").getMethod("setPermissions", String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE);
            setPermissions.invoke(null, f.getAbsolutePath(), Integer.valueOf(mode), -1, -1);
        } catch (Exception e) {
            Log.e(TAG, "chmod: " + e.getLocalizedMessage());
        }
    }

    public static void makeTextViewHtml(final Activity a, TextView tv, String s) {
        Spanned html = Html.fromHtml(s);
        tv.setText(html);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        CharSequence text = tv.getText();
        if (text instanceof Spannable) {
            Spannable sp = (Spannable) text;
            URLSpan[] urls = (URLSpan[]) sp.getSpans(0, text.length(), URLSpan.class);
            SpannableStringBuilder style = new SpannableStringBuilder(text);
            style.clearSpans();
            for (final URLSpan url : urls) {
                ClickableSpan click = new ClickableSpan() { // from class: com.roblox.client.Utils.2
                    @Override // android.text.style.ClickableSpan
                    public void onClick(View widget) {
                        Intent intent = new Intent(a, (Class<?>) ActivityWebView.class);
                        intent.putExtra("roblox_url", url.getURL());
                        a.startActivity(intent);
                    }
                };
                style.setSpan(click, sp.getSpanStart(url), sp.getSpanEnd(url), 33);
            }
            tv.setText(style);
        }
    }

    public static void doCrashGuardCheck(RobloxServiceActivity a, boolean alertIfCrashed) {
        boolean wasOk = false;
        try {
            a.openFileInput(CRASH_GUARD_OK).close();
            wasOk = true;
        } catch (Exception e) {
        }
        try {
            FileInputStream fis = a.openFileInput(CRASH_GUARD);
            byte[] buffer = new byte[256];
            int nRead = fis.read(buffer);
            char[] chars = new char[nRead];
            for (int i = 0; i < nRead; i++) {
                chars[i] = (char) buffer[i];
            }
            String str = String.copyValueOf(chars);
            if (!wasOk) {
                Log.e(TAG, "*** Found Crash Guard: " + str);
            }
            long milliseconds = Long.parseLong(str);
            long bucket = milliseconds / 1000;
            if (bucket > 0) {
                bucket = (long) Math.pow(2.0d, (long) Math.log(bucket));
            }
            if (wasOk) {
                sendTiming(a, CRASH_GUARD_CHANNEL, "TimeTeardownOk", milliseconds);
                sendTiming(a, CRASH_GUARD_CHANNEL, "TimeTeardownOk_" + bucket, milliseconds);
            } else {
                sendTiming(a, CRASH_GUARD_CHANNEL, "CrashFound", milliseconds);
                sendTiming(a, CRASH_GUARD_CHANNEL, "CrashFound_" + bucket, milliseconds);
            }
            sendTiming(a, CRASH_GUARD_CHANNEL, "Time", milliseconds);
            fis.close();
        } catch (FileNotFoundException e2) {
        } catch (Exception e3) {
            sendAnalytics(a, CRASH_GUARD_CHANNEL, "CrashFoundTimeReadFailure");
        }
        a.deleteFile(CRASH_GUARD);
        a.deleteFile(CRASH_GUARD_OK);
    }
}
