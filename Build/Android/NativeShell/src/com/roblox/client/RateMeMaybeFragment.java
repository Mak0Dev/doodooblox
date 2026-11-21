package com.roblox.client;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

/* JADX INFO: loaded from: classes.dex */
public class RateMeMaybeFragment extends DialogFragment implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    private int customIcon;
    private RMMFragInterface mInterface;
    private String message;
    private String negativeBtn;
    private String neutralBtn;
    private String positiveBtn;
    private String title;

    public interface RMMFragInterface {
        void _handleCancel();

        void _handleNegativeChoice();

        void _handleNeutralChoice();

        void _handlePositiveChoice();
    }

    public void setData(int customIcon, String title, String message, String positiveBtn, String neutralBtn, String negativeBtn, RMMFragInterface myInterface) {
        this.customIcon = customIcon;
        this.title = title;
        this.message = message;
        this.positiveBtn = positiveBtn;
        this.neutralBtn = neutralBtn;
        this.negativeBtn = negativeBtn;
        this.mInterface = myInterface;
    }

    @Override // android.app.DialogFragment, android.app.Fragment
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Override // android.app.DialogFragment
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        if (this.customIcon != 0) {
            builder.setIcon(this.customIcon);
        }
        builder.setTitle(this.title);
        builder.setMessage(this.message);
        builder.setPositiveButton(this.positiveBtn, this);
        builder.setNeutralButton(this.neutralBtn, this);
        builder.setNegativeButton(this.negativeBtn, this);
        AlertDialog alert = builder.create();
        return alert;
    }

    @Override // android.app.DialogFragment, android.content.DialogInterface.OnCancelListener
    public void onCancel(DialogInterface dialog) {
        this.mInterface._handleCancel();
    }

    @Override // android.content.DialogInterface.OnClickListener
    public void onClick(DialogInterface dialog, int choice) {
        switch (choice) {
            case -3:
                this.mInterface._handleNeutralChoice();
                break;
            case -2:
                this.mInterface._handleNegativeChoice();
                break;
            case -1:
                this.mInterface._handlePositiveChoice();
                break;
        }
    }

    @Override // android.app.DialogFragment, android.app.Fragment
    public void onDestroyView() {
        Dialog dialog = getDialog();
        if (dialog != null && getRetainInstance()) {
            dialog.setDismissMessage(null);
        }
        super.onDestroyView();
    }
}
