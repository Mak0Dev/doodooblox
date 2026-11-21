package com.roblox.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import com.google.android.gms.common.GooglePlayServicesUtil;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
@SuppressLint({"Registered"})
public class RobloxServiceActivity extends Activity {
    private static String SERVICETAG = "robloxservice";
    private Messenger mRobloxService = null;
    private Messenger mMessenger = new Messenger(new IncomingHandler());
    private List<Message> mPendingMessages = new ArrayList();
    boolean mGooglePlayServicesAvailable = false;
    private int mBindNotBound = 0;
    protected StoreManager mStoreMgr = null;
    private ServiceConnection mRobloxServiceConnection = new ServiceConnection() { // from class: com.roblox.client.RobloxServiceActivity.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName className, IBinder service) {
            RobloxServiceActivity.this.mRobloxService = new Messenger(service);
            try {
                Message msg = Message.obtain((Handler) null, 1);
                msg.replyTo = RobloxServiceActivity.this.mMessenger;
                RobloxServiceActivity.this.mRobloxService.send(msg);
                Log.i(RobloxServiceActivity.SERVICETAG, "RobloxServiceActivity.onServiceConnected success");
                RobloxServiceActivity.this.doNotifyService(1, 0);
                RobloxServiceActivity.this.onServiceConnected();
            } catch (RemoteException e) {
                Log.e(RobloxServiceActivity.SERVICETAG, "RobloxServiceActivity.onServiceConnected failed");
            }
            RobloxServiceActivity.access$406(RobloxServiceActivity.this);
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName className) {
            Log.i(RobloxServiceActivity.SERVICETAG, "RobloxServiceActivity.onServiceDisconnected");
        }
    };

    static /* synthetic */ int access$406(RobloxServiceActivity x0) {
        int i = x0.mBindNotBound - 1;
        x0.mBindNotBound = i;
        return i;
    }

    protected void onServiceNotification(int notification, int extra) {
    }

    public void onServiceConnected() {
        for (Message msg : this.mPendingMessages) {
            try {
                this.mRobloxService.send(msg);
            } catch (RemoteException e) {
                Log.e(SERVICETAG, Utils.format("RobloxActivity.doNotifyService failed service dead", new Object[0]));
            }
        }
        this.mPendingMessages.clear();
    }

    @SuppressLint({"HandlerLeak"})
    private class IncomingHandler extends Handler {
        private IncomingHandler() {
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 3:
                    RobloxServiceActivity.this.onServiceNotification(msg.arg1, msg.arg2);
                    Log.i(RobloxServiceActivity.SERVICETAG, String.format("onServiceNotification %d %d", Integer.valueOf(msg.arg1), Integer.valueOf(msg.arg2)));
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    protected void doBindService() {
        this.mBindNotBound++;
        Intent intent = new Intent(this, (Class<?>) RobloxService.class);
        boolean debuggerAttached = Debug.isDebuggerConnected();
        intent.putExtra("roblox_launcher_debugger_attached", debuggerAttached);
        boolean isOk = bindService(intent, this.mRobloxServiceConnection, 1);
        if (!isOk) {
            Log.e(SERVICETAG, Utils.format("RobloxActivity.doBindService failed", new Object[0]));
        }
    }

    protected void doUnbindService() {
        if (this.mRobloxService != null) {
            try {
                Message msg = Message.obtain((Handler) null, 2);
                msg.replyTo = this.mMessenger;
                this.mRobloxService.send(msg);
            } catch (RemoteException e) {
                Log.e(SERVICETAG, Utils.format("RobloxActivity.doUnbindService wtf service already dead.", new Object[0]));
            }
            this.mRobloxService = null;
        } else {
            this.mBindNotBound--;
        }
        unbindService(this.mRobloxServiceConnection);
    }

    protected void doNotifyService(int notification, int extra) {
        Message msg = Message.obtain(null, 3, notification, extra);
        if (this.mRobloxService == null) {
            this.mPendingMessages.add(msg);
            return;
        }
        try {
            this.mRobloxService.send(msg);
        } catch (RemoteException e) {
            Log.e(SERVICETAG, Utils.format("RobloxActivity.doNotifyService failed service dead", new Object[0]));
        }
    }

    @Override // android.app.Activity
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int res = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if (res != 0) {
            Log.w(SERVICETAG, "isGooglePlayServicesAvailable: No");
        } else {
            this.mGooglePlayServicesAvailable = true;
        }
        this.mStoreMgr = StoreManager.getStoreManager(this);
        Intent intent = new Intent(this, (Class<?>) RobloxService.class);
        startService(intent);
    }

    @Override // android.app.Activity
    protected void onStart() {
        super.onStart();
        doBindService();
    }

    @Override // android.app.Activity
    protected void onStop() {
        doUnbindService();
        Utils.contextSwitchExclusiveDialog(this);
        if (this.mBindNotBound > 0) {
            Log.e(SERVICETAG, "*** mBindNotBound: " + this.mBindNotBound);
        }
        super.onStop();
    }

    @Override // android.app.Activity
    protected void onResume() {
        Log.i("StoreManager", "Handle onResume in RobloxService Activity");
        super.onResume();
        this.mStoreMgr.handleActivityResume();
    }

    @Override // android.app.Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!this.mStoreMgr.handleActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        } else {
            Log.d(SERVICETAG, "onActivityResult handled by Store Manager");
        }
    }

    protected boolean isGooglePlayServicesAvailable() {
        return this.mGooglePlayServicesAvailable;
    }
}
