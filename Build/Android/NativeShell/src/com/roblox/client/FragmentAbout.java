package com.roblox.client;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/* JADX INFO: loaded from: classes.dex */
public class FragmentAbout extends Fragment {
    private View mBackgroundView = null;
    private View mDialogueView = null;
    private TextView mTermsLicenscingPrivacy = null;
    private TextView mUserAgent = null;
    private TextView mBaseUrl = null;

    @Override // android.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view;
        if (RobloxSettings.isPhone()) {
            view = inflater.inflate(R.layout.fragment_about_phone, container, false);
        } else {
            view = inflater.inflate(R.layout.fragment_about, container, false);
        }
        this.mBackgroundView = view.findViewById(R.id.fragment_about_background);
        this.mDialogueView = view.findViewById(R.id.fragment_about_dialog_bg);
        this.mTermsLicenscingPrivacy = (TextView) view.findViewById(R.id.fragment_about_terms_licenscing_privacy);
        this.mUserAgent = (TextView) view.findViewById(R.id.fragment_about_user_agent);
        this.mBaseUrl = (TextView) view.findViewById(R.id.fragment_about_baseURL);
        this.mBackgroundView.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.FragmentAbout.1
            @Override // android.view.View.OnClickListener
            public void onClick(View x) {
                FragmentAbout.this.getActivity().getFragmentManager().beginTransaction().remove(FragmentAbout.this).commit();
            }
        });
        if (this.mDialogueView != null) {
            this.mDialogueView.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.FragmentAbout.2
                @Override // android.view.View.OnClickListener
                public void onClick(View x) {
                }
            });
        }
        String res = getString(R.string.TermsLicensingPrivacy);
        Utils.makeTextViewHtml(getActivity(), this.mTermsLicenscingPrivacy, res);
        this.mUserAgent.setText(RobloxSettings.userAgent());
        this.mBaseUrl.setText(RobloxSettings.baseUrl());
        return view;
    }

    @Override // android.app.Fragment
    public void onResume() {
        super.onResume();
        if (getActivity() != null) {
            Utils.sendAnalyticsScreen((RobloxServiceActivity) getActivity(), "AboutScreen");
        }
    }
}
