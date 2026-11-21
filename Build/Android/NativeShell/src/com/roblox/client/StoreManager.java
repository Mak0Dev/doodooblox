package com.roblox.client;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import com.amazon.device.iap.PurchasingListener;
import com.amazon.device.iap.PurchasingService;
import com.amazon.device.iap.model.FulfillmentResult;
import com.amazon.device.iap.model.Product;
import com.amazon.device.iap.model.ProductDataResponse;
import com.amazon.device.iap.model.PurchaseResponse;
import com.amazon.device.iap.model.PurchaseUpdatesResponse;
import com.amazon.device.iap.model.Receipt;
import com.amazon.device.iap.model.UserDataResponse;
import com.google.android.gms.games.GamesActivityResultCodes;
import com.roblox.iab.IabHelper;
import com.roblox.iab.IabResult;
import com.roblox.iab.Inventory;
import com.roblox.iab.Purchase;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/* JADX INFO: loaded from: classes.dex */
public class StoreManager {
    protected static final String PENDING_PURCHASE_AMAZON_RECEIPT_ID = "AmazonReceiptIDForPendingAmazonPurchase";
    protected static final String PENDING_PURCHASE_AMAZON_USER_ID = "AmazonUserIDForPendingAmazonPurchase";
    protected static final String PENDING_PURCHASE_ROBLOX_USER_NAME = "RobloxUserNameForPendingAmazonPurchase";
    protected static final String TAG = "StoreManager";
    private static StoreManager storeMgr = null;
    public StoreManagerType iabStoreType;
    private RobloxServiceActivity mActivity;
    private Context mContext;
    private IabHelper mGoogleIABHelper;
    private String mProductId = null;
    private String mUserName = "";
    private long mPlayerPtr = 0;
    private boolean bDebugPurchasing = false;
    IabHelper.QueryInventoryFinishedListener mGotInventoryListener = new IabHelper.QueryInventoryFinishedListener() { // from class: com.roblox.client.StoreManager.2
        @Override // com.roblox.iab.IabHelper.QueryInventoryFinishedListener
        public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
            StoreManager.this.printLogMessage("Query inventory finished.");
            if (StoreManager.this.mGoogleIABHelper != null) {
                if (result.isFailure()) {
                    StoreManager.this.printLogMessage("Failed to query inventory: " + result);
                    return;
                }
                StoreManager.this.printLogMessage("Query inventory was successful.");
                List<Purchase> purchases = inventory.getAllPurchases();
                ListIterator<Purchase> it = purchases.listIterator(purchases.size());
                while (it.hasPrevious()) {
                    Purchase purchase = it.previous();
                    if (StoreManager.this.mUserName.equals(purchase.getDeveloperPayload())) {
                        StoreManager.this.verifyDeveloperPayload(purchase, true);
                    }
                }
                if (StoreManager.this.bDebugPurchasing) {
                    StoreManager.this.mGoogleIABHelper.consumeAsync(purchases, (IabHelper.OnConsumeMultiFinishedListener) null);
                }
            }
        }
    };
    IabHelper.OnIabPurchaseFinishedListener mPurchaseFinishedListener = new IabHelper.OnIabPurchaseFinishedListener() { // from class: com.roblox.client.StoreManager.3
        @Override // com.roblox.iab.IabHelper.OnIabPurchaseFinishedListener
        public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
            StoreManager.this.printLogMessage("Purchase finished: " + result + ", purchase: " + purchase);
            if (StoreManager.this.mGoogleIABHelper != null) {
                if (result.isFailure()) {
                    Utils.alertExclusively(StoreManager.this.mActivity, "Purchasing cancelled: " + result);
                    StoreManager.this.grantPendingPurchases();
                    ActivityGlView.inGamePurchaseFinished(false, StoreManager.this.mPlayerPtr, StoreManager.this.mProductId);
                    return;
                }
                StoreManager.this.verifyDeveloperPayload(purchase, false);
            }
        }
    };
    IabHelper.OnConsumeFinishedListener mConsumeFinishedListener = new IabHelper.OnConsumeFinishedListener() { // from class: com.roblox.client.StoreManager.4
        @Override // com.roblox.iab.IabHelper.OnConsumeFinishedListener
        public void onConsumeFinished(Purchase purchase, IabResult result) {
            StoreManager.this.printLogMessage("Consumption finished. Purchase: " + purchase + ", result: " + result);
            if (StoreManager.this.mGoogleIABHelper != null) {
                if (result.isSuccess()) {
                    Utils.alertExclusively(StoreManager.this.mActivity, "Purchase successful, your product will be delivered shortly.");
                    ActivityGlView.inGamePurchaseFinished(true, StoreManager.this.mPlayerPtr, StoreManager.this.mProductId);
                } else {
                    Utils.alertExclusively(StoreManager.this.mActivity, "Error while purchasing. Receipt verification failed: " + result);
                    ActivityGlView.inGamePurchaseFinished(false, StoreManager.this.mPlayerPtr, StoreManager.this.mProductId);
                }
                StoreManager.this.resetPurchaseData();
                StoreManager.this.printLogMessage("End consumption flow.");
            }
        }
    };
    private SharedPreferences mKeyValues = RobloxSettings.mKeyValues;

    private enum StoreManagerType {
        IAB_GOOGLE,
        IAB_AMAZON,
        IAB_NONE,
        IAB_UNKNOWN
    }

    void printLogMessage(String message) {
        if (this.bDebugPurchasing) {
            Log.d(TAG, message);
        }
    }

    public class AmazonPurchasingListener implements PurchasingListener {
        private static final String TAG = "AmazonPurchasingListener";
        private boolean rvsProductionMode;
        private String currentUserId = null;
        private String currentMarketplace = null;

        public AmazonPurchasingListener() {
            this.rvsProductionMode = false;
            this.rvsProductionMode = PurchasingService.IS_SANDBOX_MODE ? false : true;
            StoreManager.this.iabStoreType = StoreManagerType.IAB_AMAZON;
        }

        @Override // com.amazon.device.iap.PurchasingListener
        public void onUserDataResponse(UserDataResponse response) {
            UserDataResponse.RequestStatus status = response.getRequestStatus();
            switch (status) {
                case SUCCESSFUL:
                    this.currentUserId = response.getUserData().getUserId();
                    this.currentMarketplace = response.getUserData().getMarketplace();
                    StoreManager.this.iabStoreType = StoreManagerType.IAB_AMAZON;
                    break;
                case FAILED:
                case NOT_SUPPORTED:
                    StoreManager.this.iabStoreType = StoreManagerType.IAB_NONE;
                    break;
            }
        }

        @Override // com.amazon.device.iap.PurchasingListener
        public void onProductDataResponse(ProductDataResponse response) {
            switch (response.getRequestStatus()) {
                case SUCCESSFUL:
                    for (String s : response.getUnavailableSkus()) {
                        StoreManager.this.printLogMessage("Unavailable SKU:" + s);
                    }
                    Map<String, Product> products = response.getProductData();
                    for (String key : products.keySet()) {
                        Product product = products.get(key);
                        StoreManager.this.printLogMessage(String.format("Product: %s\n Type: %s\n SKU: %s\n Price: %s\n Description: %s\n", product.getTitle(), product.getProductType(), product.getSku(), product.getPrice(), product.getDescription()));
                    }
                    break;
                case FAILED:
                    StoreManager.this.printLogMessage("ProductDataRequestStatus: FAILED");
                    break;
            }
        }

        @Override // com.amazon.device.iap.PurchasingListener
        public void onPurchaseUpdatesResponse(PurchaseUpdatesResponse response) {
            StoreManager.this.printLogMessage("Purchase Update Response");
            String pendingAmazonPurchaseRobloxUserName = StoreManager.this.mKeyValues.getString(StoreManager.PENDING_PURCHASE_ROBLOX_USER_NAME, "");
            switch (response.getRequestStatus()) {
                case SUCCESSFUL:
                    for (Receipt receipt : response.getReceipts()) {
                        String receiptId = receipt.getReceiptId();
                        String userId = response.getUserData().getUserId();
                        StoreManager.this.printLogMessage("Amazon: receiptID: " + receiptId + "userID: " + userId);
                        if (StoreManager.this.mUserName.isEmpty() || (!pendingAmazonPurchaseRobloxUserName.equals(StoreManager.this.mUserName) && !pendingAmazonPurchaseRobloxUserName.isEmpty())) {
                            SharedPreferences.Editor editor = StoreManager.this.mKeyValues.edit();
                            editor.putString(StoreManager.PENDING_PURCHASE_AMAZON_USER_ID, userId);
                            editor.putString(StoreManager.PENDING_PURCHASE_AMAZON_RECEIPT_ID, receiptId);
                            editor.apply();
                        } else {
                            StoreManager.this.printLogMessage("Sending Retry Receipt for verification: receiptID: " + receiptId + "userID: " + userId);
                            new VerifyPurchaseReceipt(StoreManager.this.mActivity, StoreManager.storeMgr, null, null, true, null, receiptId, userId, this).execute(new Void[0]);
                        }
                    }
                    break;
                case FAILED:
                    StoreManager.this.printLogMessage("On Purchase Update Response Status Failed");
                    break;
                default:
                    String pendingAmazonPurchaseUserID = StoreManager.this.mKeyValues.getString(StoreManager.PENDING_PURCHASE_AMAZON_USER_ID, "");
                    String pendingAmazonPurchaseReceiptID = StoreManager.this.mKeyValues.getString(StoreManager.PENDING_PURCHASE_AMAZON_RECEIPT_ID, "");
                    if (!StoreManager.this.mUserName.isEmpty() && !pendingAmazonPurchaseReceiptID.isEmpty() && !pendingAmazonPurchaseUserID.isEmpty()) {
                        if (pendingAmazonPurchaseRobloxUserName.equals(StoreManager.this.mUserName) || pendingAmazonPurchaseRobloxUserName.isEmpty()) {
                            StoreManager.this.printLogMessage("Sending Retry Receipt for verification: receiptID: " + pendingAmazonPurchaseReceiptID + "userID: " + pendingAmazonPurchaseUserID);
                            new VerifyPurchaseReceipt(StoreManager.this.mActivity, StoreManager.storeMgr, null, null, true, null, pendingAmazonPurchaseReceiptID, pendingAmazonPurchaseUserID, this).execute(new Void[0]);
                        }
                        break;
                    }
                    break;
            }
        }

        @Override // com.amazon.device.iap.PurchasingListener
        public void onPurchaseResponse(PurchaseResponse response) {
            PurchaseResponse.RequestStatus status = response.getRequestStatus();
            if (status != PurchaseResponse.RequestStatus.SUCCESSFUL) {
                ActivityGlView.inGamePurchaseFinished(false, StoreManager.this.mPlayerPtr, StoreManager.this.mProductId);
                return;
            }
            Receipt receipt = response.getReceipt();
            String receiptId = receipt.getReceiptId();
            String userId = response.getUserData().getUserId();
            SharedPreferences.Editor editor = StoreManager.this.mKeyValues.edit();
            editor.putString(StoreManager.PENDING_PURCHASE_ROBLOX_USER_NAME, StoreManager.this.mUserName);
            editor.apply();
            if (!StoreManager.this.mUserName.isEmpty()) {
                StoreManager.this.printLogMessage("Sending Fresh Receipt for verification: receiptID: " + receiptId + "userID: " + userId);
                new VerifyPurchaseReceipt(StoreManager.this.mActivity, StoreManager.storeMgr, null, null, false, null, receiptId, userId, this).execute(new Void[0]);
            }
        }

        public void consumePurchase(String receiptId, boolean isRetry) {
            StoreManager.this.printLogMessage("Consuming Receipt: receiptID: " + receiptId);
            if (!receiptId.isEmpty()) {
                StoreManager.this.printLogMessage("Fulfilled: receiptID: " + receiptId);
                PurchasingService.notifyFulfillment(receiptId, FulfillmentResult.FULFILLED);
                if (!isRetry) {
                    Utils.alertExclusively(StoreManager.this.mActivity, "Purchase successful, your product will be delivered shortly.");
                }
                ActivityGlView.inGamePurchaseFinished(true, StoreManager.this.mPlayerPtr, StoreManager.this.mProductId);
            } else {
                ActivityGlView.inGamePurchaseFinished(false, StoreManager.this.mPlayerPtr, StoreManager.this.mProductId);
            }
            StoreManager.this.resetPurchaseData();
            StoreManager.this.printLogMessage("End consumption flow.");
        }
    }

    protected StoreManager(Context ctx) {
        this.iabStoreType = StoreManagerType.IAB_UNKNOWN;
        this.mContext = ctx;
        String deviceBrand = Build.BRAND;
        if (deviceBrand.equals("Amazon")) {
            PurchasingService.registerListener(this.mContext, new AmazonPurchasingListener());
            printLogMessage("onCreate: sandbox mode is:" + PurchasingService.IS_SANDBOX_MODE);
            this.iabStoreType = StoreManagerType.IAB_AMAZON;
        } else {
            this.mGoogleIABHelper = new IabHelper(this.mContext, "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0gQsTOERl7cAXlms9RMRN+XhTyE9h1oX/Wubr0x6FthR6gqktjdHOJ7ge6RR5Tbdpnv9/uhBMjk2hZOG/UktG8gxHbC0FZYdgm2T56tZrkpmodVk3+jN4gPBDIDhSPoKPIbu0dCbiTNOudL68nJVj+jRZI3nk4UDATTktWL7mzHkkt2B9BvKoA7MLJdGVhOSQzMgcTycI14em75apxWliIUDPz11L2USvIddTT+oPvRqLGe+BmIIvS5rCqdEqpLN4G0Qn6ioCw5R6I+kBy0cDY1604Vs5/FEPI+fSk48Kme+peiX+hGVlPF6ZZ9jF6yT/vAjpvn/DEEwkMMa7JnmhwIDAQAB");
            this.mGoogleIABHelper.enableDebugLogging(this.bDebugPurchasing);
            this.mGoogleIABHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() { // from class: com.roblox.client.StoreManager.1
                @Override // com.roblox.iab.IabHelper.OnIabSetupFinishedListener
                public void onIabSetupFinished(IabResult result) {
                    if (!result.isSuccess()) {
                        StoreManager.this.printLogMessage("Google IAB is not setup");
                        StoreManager.this.mGoogleIABHelper = null;
                        StoreManager.this.iabStoreType = StoreManagerType.IAB_NONE;
                        return;
                    }
                    StoreManager.this.printLogMessage("Google IAB is setup");
                    StoreManager.this.iabStoreType = StoreManagerType.IAB_GOOGLE;
                }
            });
        }
    }

    public static StoreManager getStoreManager(Context ctx) {
        if (storeMgr == null) {
            storeMgr = new StoreManager(ctx);
        }
        return storeMgr;
    }

    private boolean internalDoInAppPurchase() {
        if (!purchasingEnabled()) {
            return false;
        }
        try {
            if (this.mGoogleIABHelper != null && this.iabStoreType == StoreManagerType.IAB_GOOGLE) {
                printLogMessage("Do Google Purchase Initiate");
                this.mGoogleIABHelper.launchPurchaseFlow(this.mActivity, this.mProductId.toString(), GamesActivityResultCodes.RESULT_RECONNECT_REQUIRED, this.mPurchaseFinishedListener, this.mUserName);
            } else if (this.iabStoreType == StoreManagerType.IAB_AMAZON) {
                String pendingAmazonPurchaseUserName = this.mKeyValues.getString(PENDING_PURCHASE_ROBLOX_USER_NAME, "");
                if (pendingAmazonPurchaseUserName.isEmpty()) {
                    printLogMessage("Do Amazon Purchase Initiate");
                    PurchasingService.purchase(this.mProductId.toString());
                } else {
                    Utils.alertExclusively(this.mActivity, "There is a pending Purchase with ROBLOX User Name. Please login with User Name: " + pendingAmazonPurchaseUserName);
                    grantPendingPurchases();
                }
            }
            return true;
        } catch (IllegalStateException e) {
            Utils.alertUnexpectedError(this.mActivity, "StoreManager IllegalStateException");
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetPurchaseData() {
        this.mUserName = "";
        this.mActivity = null;
        this.mProductId = "";
        this.mPlayerPtr = 0L;
        SharedPreferences.Editor editor = this.mKeyValues.edit();
        editor.remove(PENDING_PURCHASE_ROBLOX_USER_NAME);
        editor.remove(PENDING_PURCHASE_AMAZON_USER_ID);
        editor.remove(PENDING_PURCHASE_AMAZON_RECEIPT_ID);
        editor.apply();
    }

    public boolean doInAppPurchaseForProduct(RobloxServiceActivity activity, String productId, String userName, long playerPtr) {
        this.mUserName = userName;
        this.mActivity = activity;
        this.mProductId = productId;
        this.mPlayerPtr = playerPtr;
        return internalDoInAppPurchase();
    }

    public void grantPendingPurchases() {
        if (this.mGoogleIABHelper != null && this.iabStoreType == StoreManagerType.IAB_GOOGLE) {
            printLogMessage("Do Amazon Purchase GrantPending");
            this.mGoogleIABHelper.queryInventoryAsync(this.mGotInventoryListener);
        } else if (this.iabStoreType == StoreManagerType.IAB_AMAZON) {
            printLogMessage("Do Amazon Purchase GrantPending");
            PurchasingService.getPurchaseUpdates(true);
        }
    }

    public boolean doInAppPurchaseForUrl(RobloxServiceActivity activity, String urlString, String userName) {
        this.mUserName = userName;
        this.mActivity = activity;
        Uri uriObject = Uri.parse(urlString);
        this.mProductId = uriObject.getQueryParameter("id");
        return internalDoInAppPurchase();
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        printLogMessage("onActivityResult(" + requestCode + "," + resultCode + "," + data);
        return (this.iabStoreType != StoreManagerType.IAB_GOOGLE || this.mGoogleIABHelper == null || this.mGoogleIABHelper.handleActivityResult(requestCode, resultCode, data)) ? false : true;
    }

    public void handleActivityResume() {
        if (this.iabStoreType == StoreManagerType.IAB_AMAZON) {
            printLogMessage("Handle Activity Resume");
            PurchasingService.getUserData();
            grantPendingPurchases();
        }
    }

    boolean purchasingEnabled() {
        if (this.mUserName.isEmpty()) {
            return false;
        }
        if (this.iabStoreType != StoreManagerType.IAB_AMAZON && this.iabStoreType != StoreManagerType.IAB_GOOGLE) {
            return false;
        }
        return true;
    }

    void verifyDeveloperPayload(Purchase p, boolean isRetry) {
        new VerifyPurchaseReceipt(this.mActivity, this, p, this.mGoogleIABHelper, isRetry, isRetry ? null : this.mConsumeFinishedListener, "", "", null).execute(new Void[0]);
    }

    public void destroy() {
        if (this.mGoogleIABHelper != null) {
            this.mGoogleIABHelper.dispose();
        }
        this.mGoogleIABHelper = null;
        storeMgr = null;
    }

    static class VerifyPurchaseReceipt extends AsyncTask<Void, Void, Void> {
        AmazonPurchasingListener mAmazonListener;
        String mAmazonReceiptId;
        String mAmazonUserId;
        IabHelper.OnConsumeFinishedListener mConsumeFinishListener;
        IabHelper mInAppBillingHelper;
        boolean mIsRetry;
        Purchase mPurchase;
        Activity mRobloxActivity;
        String mStatus;
        StoreManager mStoreMgr;
        String mResponse = null;
        String upgradeCheckUrl = RobloxSettings.upgradeCheckUrl();

        VerifyPurchaseReceipt(Activity activity, StoreManager mgr, Purchase p, IabHelper h, boolean isRetry, IabHelper.OnConsumeFinishedListener listener, String amazonReceiptId, String amazonUserId, AmazonPurchasingListener amazonListener) {
            this.mRobloxActivity = null;
            this.mRobloxActivity = activity;
            this.mPurchase = p;
            this.mInAppBillingHelper = h;
            this.mConsumeFinishListener = listener;
            this.mStoreMgr = mgr;
            this.mIsRetry = isRetry;
            this.mAmazonReceiptId = amazonReceiptId;
            this.mAmazonUserId = amazonUserId;
            this.mAmazonListener = amazonListener;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... uri) {
            String urlVerify;
            String retry = this.mIsRetry ? "true" : "false";
            if (this.mStoreMgr.iabStoreType == StoreManagerType.IAB_GOOGLE) {
                urlVerify = RobloxSettings.verifyPurchaseReceiptUrlForGoogle() + "packageName=" + this.mPurchase.getPackageName() + "&productId=" + this.mPurchase.getSku() + "&orderId=" + this.mPurchase.getOrderId() + "&isRetry=" + retry + "&token=" + this.mPurchase.getToken();
            } else {
                if (this.mStoreMgr.iabStoreType == StoreManagerType.IAB_AMAZON) {
                    urlVerify = RobloxSettings.verifyPurchaseReceiptUrlForAmazon() + "receiptId=" + this.mAmazonReceiptId + "&amazonUserId=" + this.mAmazonUserId + "&isRetry=" + retry;
                }
                return null;
            }
            this.mResponse = HttpAgent.readUrlToString(urlVerify, "", null);
            return null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            if (this.mResponse == null) {
                Utils.alertIfNetworkNotConnected(this.mRobloxActivity);
                Log.d(StoreManager.TAG, "Response NULL");
                return;
            }
            try {
                if (this.mResponse.equals("OK")) {
                    Utils.sendAnalytics((RobloxServiceActivity) this.mRobloxActivity, StoreManager.TAG, "PurchaseOK");
                    Log.d(StoreManager.TAG, "Receipt Verification Successful");
                    if (this.mStoreMgr.iabStoreType == StoreManagerType.IAB_GOOGLE) {
                        this.mInAppBillingHelper.consumeAsync(this.mPurchase, this.mConsumeFinishListener);
                    } else if (this.mStoreMgr.iabStoreType == StoreManagerType.IAB_AMAZON) {
                        this.mAmazonListener.consumePurchase(this.mAmazonReceiptId, this.mIsRetry);
                    }
                } else if (this.mResponse.equals("error") || this.mResponse.equals("Error")) {
                    Log.e(StoreManager.TAG, "ROBLOX Billing service is down");
                    Utils.sendAnalytics((RobloxServiceActivity) this.mRobloxActivity, StoreManager.TAG, "PurchaseFailedDueToBillingServiceFailed");
                } else if (this.mResponse.equals("Bogus") || this.mResponse.equals("bogus")) {
                    Log.e(StoreManager.TAG, "Bogus Amazon Receipt");
                    Utils.sendAnalytics((RobloxServiceActivity) this.mRobloxActivity, StoreManager.TAG, "BogusAmazonReceiptDetected");
                    if (this.mStoreMgr.iabStoreType == StoreManagerType.IAB_AMAZON) {
                        this.mAmazonListener.consumePurchase("", this.mIsRetry);
                    }
                }
            } catch (Exception e) {
            }
        }
    }
}
