package com.roblox.client;

import android.content.Intent;
import android.os.Bundle;
import com.jirbo.adcolony.AdColony;
import com.jirbo.adcolony.AdColonyAd;
import com.jirbo.adcolony.AdColonyAdListener;
import com.jirbo.adcolony.AdColonyVideoAd;

/* JADX INFO: loaded from: classes.dex */
public class ActivityAdColony extends RobloxActivity implements AdColonyAdListener {
    @Override // com.roblox.client.RobloxActivity, com.roblox.client.RobloxServiceActivity, android.app.Activity
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override // com.roblox.client.RobloxActivity, com.roblox.client.RobloxServiceActivity, android.app.Activity
    protected void onResume() {
        super.onResume();
        AdColony.resume(this);
        showVideoAd();
    }

    @Override // com.roblox.client.RobloxActivity, android.app.Activity
    protected void onPause() {
        super.onPause();
        AdColony.pause();
    }

    private void showVideoAd() {
        AdColonyVideoAd ad = new AdColonyVideoAd().withListener((AdColonyAdListener) this);
        ad.show();
    }

    @Override // com.jirbo.adcolony.AdColonyAdListener
    public void onAdColonyAdStarted(AdColonyAd ad) {
    }

    @Override // com.jirbo.adcolony.AdColonyAdListener
    public void onAdColonyAdAttemptFinished(AdColonyAd ad) {
        Intent intent = new Intent(this, (Class<?>) ActivityAdColony.class);
        intent.putExtra("shown", ad.shown());
        setResult(1, intent);
        finish();
    }
}
