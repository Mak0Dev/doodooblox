package com.roblox.client;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import com.flurry.android.Constants;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class FragmentSignUp extends Fragment {
    private static Button mBirthday = null;
    static int mYear = 2000;
    static int mMonth = 0;
    static int mDay = 1;
    static boolean mDateSelected = false;
    private SignUpCallbacks mSignUpCallbacks = null;
    private View mBackgroundView = null;
    private TextView mCancelTextView = null;
    private EditText mUsernameEditText = null;
    private ImageView mUsernameCheckImageView = null;
    private EditText mPasswordEditText = null;
    private ImageView mPasswordCheckImageView = null;
    private EditText mPasswordVerifyEditText = null;
    private ImageView mPasswordVerifyCheckImageView = null;
    private Spinner mGenderSpinner = null;
    private EditText mEmailEditText = null;
    private ImageView mEmailCheckImageView = null;
    private Button mSignUpButton = null;
    private TextView mAgreementTextView = null;
    private String mUsername = null;
    private String mPassword = null;
    private String mPasswordVerify = null;
    private String mEmail = null;
    private int mGender = 0;
    private boolean mIsCanceling = false;
    private Validation mUsernameValidation = Validation.BLANK;
    private Validation mPasswordValidation = Validation.BLANK;
    private Validation mPasswordVerifyValidation = Validation.BLANK;
    private Validation mEmailVerifyValidation = Validation.BLANK;
    private String s3 = "Z^#q";
    private String s1 = "Fu.*mJ";
    private String s4 = "l%=f~RIW";
    private String s2 = "L65HQ,v?K";
    private String s5 = "hC39$";
    private String s7 = "qb@Wl";
    private String s8 = "Av=M";
    private String s10 = "B7YpO";
    private String s6 = "jEda0J~i";
    private String s9 = "HZmfcyG9,F";

    interface SignUpCallbacks {
        void onFragmentSignUpCancel();

        void onFragmentSignUpOk(JSONObject jSONObject);
    }

    private enum Validation {
        BLANK,
        VALID,
        INVALID
    }

    private enum ValidationOp {
        USERNAME,
        PASSWORD
    }

    public void setSignUpCallbacks(SignUpCallbacks callbacks) {
        this.mSignUpCallbacks = callbacks;
    }

    @Override // android.app.Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view;
        if (RobloxSettings.isPhone()) {
            view = inflater.inflate(R.layout.fragment_sign_up_phone, container, false);
        } else {
            view = inflater.inflate(R.layout.fragment_sign_up, container, false);
        }
        this.mBackgroundView = view.findViewById(R.id.fragment_sign_up_background);
        this.mCancelTextView = (TextView) view.findViewById(R.id.fragment_sign_up_cancel);
        this.mUsernameEditText = (EditText) view.findViewById(R.id.fragment_sign_up_username);
        this.mUsernameCheckImageView = (ImageView) view.findViewById(R.id.fragment_sign_up_username_checked);
        this.mPasswordEditText = (EditText) view.findViewById(R.id.fragment_sign_up_password);
        this.mPasswordCheckImageView = (ImageView) view.findViewById(R.id.fragment_sign_up_password_checked);
        this.mPasswordVerifyEditText = (EditText) view.findViewById(R.id.fragment_sign_up_password_verify);
        this.mPasswordVerifyCheckImageView = (ImageView) view.findViewById(R.id.fragment_sign_up_password_verify_checked);
        mBirthday = (Button) view.findViewById(R.id.fragment_sign_up_birthday);
        this.mGenderSpinner = (Spinner) view.findViewById(R.id.fragment_sign_up_gender_spinner);
        this.mEmailEditText = (EditText) view.findViewById(R.id.fragment_sign_up_email);
        this.mEmailCheckImageView = (ImageView) view.findViewById(R.id.fragment_sign_up_email_checked);
        this.mSignUpButton = (Button) view.findViewById(R.id.fragment_sign_up_button);
        this.mAgreementTextView = (TextView) view.findViewById(R.id.fragment_sign_up_agreement);
        setNextButton(this.mUsernameEditText, this.mPasswordEditText);
        setNextButton(this.mPasswordEditText, this.mPasswordVerifyEditText);
        this.mBackgroundView.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.FragmentSignUp.1
            @Override // android.view.View.OnClickListener
            public void onClick(View x) {
            }
        });
        this.mCancelTextView.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.FragmentSignUp.2
            @Override // android.view.View.OnClickListener
            public void onClick(View x) {
                if (FragmentSignUp.this.mSignUpCallbacks != null) {
                    FragmentSignUp.this.mIsCanceling = true;
                    InputMethodManager inputManager = (InputMethodManager) FragmentSignUp.this.getActivity().getSystemService("input_method");
                    inputManager.hideSoftInputFromWindow(FragmentSignUp.this.getActivity().getCurrentFocus().getWindowToken(), 2);
                    FragmentSignUp.this.mSignUpCallbacks.onFragmentSignUpCancel();
                }
            }
        });
        this.mUsernameEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() { // from class: com.roblox.client.FragmentSignUp.3
            @Override // android.view.View.OnFocusChangeListener
            public void onFocusChange(View v, boolean hasFocus) {
                if (!FragmentSignUp.this.mIsCanceling && !hasFocus) {
                    FragmentSignUp.this.doValidationTask(ValidationOp.USERNAME);
                }
            }
        });
        this.mPasswordEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() { // from class: com.roblox.client.FragmentSignUp.4
            @Override // android.view.View.OnFocusChangeListener
            public void onFocusChange(View v, boolean hasFocus) {
                if (!FragmentSignUp.this.mIsCanceling && !hasFocus) {
                    FragmentSignUp.this.doValidationTask(ValidationOp.PASSWORD);
                }
            }
        });
        this.mPasswordVerifyEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() { // from class: com.roblox.client.FragmentSignUp.5
            @Override // android.view.View.OnFocusChangeListener
            public void onFocusChange(View v, boolean hasFocus) {
                if (!FragmentSignUp.this.mIsCanceling && !hasFocus) {
                    FragmentSignUp.this.doPasswordVerifyValidation(true);
                }
            }
        });
        this.mPasswordVerifyEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.roblox.client.FragmentSignUp.6
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != 6) {
                    return false;
                }
                FragmentSignUp.this.mPasswordVerifyEditText.clearFocus();
                FragmentSignUp.this.doPasswordVerifyValidation(true);
                InputMethodManager inputManager = (InputMethodManager) FragmentSignUp.this.getActivity().getSystemService("input_method");
                inputManager.hideSoftInputFromWindow(FragmentSignUp.this.getActivity().getCurrentFocus().getWindowToken(), 2);
                return true;
            }
        });
        mBirthday.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.FragmentSignUp.7
            @Override // android.view.View.OnClickListener
            public void onClick(View v) {
                DialogFragment newFragment = new DatePickerFragment();
                newFragment.show(FragmentSignUp.this.getFragmentManager(), (String) null);
                if (FragmentSignUp.this.getActivity().getCurrentFocus() != null) {
                    FragmentSignUp.this.getActivity().getCurrentFocus().clearFocus();
                }
            }
        });
        this.mEmailEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() { // from class: com.roblox.client.FragmentSignUp.8
            @Override // android.view.View.OnFocusChangeListener
            public void onFocusChange(View v, boolean hasFocus) {
                if (!FragmentSignUp.this.mIsCanceling && !hasFocus) {
                    FragmentSignUp.this.doEmailValidation();
                }
            }
        });
        this.mEmailEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.roblox.client.FragmentSignUp.9
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != 6) {
                    return false;
                }
                FragmentSignUp.this.mEmailEditText.clearFocus();
                FragmentSignUp.this.doEmailValidation();
                InputMethodManager inputManager = (InputMethodManager) FragmentSignUp.this.getActivity().getSystemService("input_method");
                inputManager.hideSoftInputFromWindow(FragmentSignUp.this.getActivity().getCurrentFocus().getWindowToken(), 2);
                return true;
            }
        });
        this.mSignUpButton.setOnClickListener(new View.OnClickListener() { // from class: com.roblox.client.FragmentSignUp.10
            @Override // android.view.View.OnClickListener
            public void onClick(View v) {
                FragmentSignUp.this.onSignUpClicked();
            }
        });
        ArrayAdapter<CharSequence> genderAdapter = ArrayAdapter.createFromResource(getActivity(), R.array.GenderArray, android.R.layout.simple_spinner_item);
        genderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        this.mGenderSpinner.setAdapter((SpinnerAdapter) genderAdapter);
        this.mGenderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.roblox.client.FragmentSignUp.11
            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onItemSelected(AdapterView<?> parent, View view2, int position, long id) {
                FragmentSignUp.this.getActivity().getCurrentFocus().clearFocus();
                FragmentSignUp.this.mGender = position;
            }

            @Override // android.widget.AdapterView.OnItemSelectedListener
            public void onNothingSelected(AdapterView<?> parent) {
                FragmentSignUp.this.getActivity().getCurrentFocus().clearFocus();
                FragmentSignUp.this.mGender = 0;
            }
        });
        Utils.makeTextViewHtml(getActivity(), this.mAgreementTextView, getString(R.string.SignupFinePrintAndroid));
        return view;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String computeHash(String input) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.reset();
        byte[] byteData = digest.digest(input.getBytes("UTF-8"));
        StringBuffer sb = new StringBuffer();
        for (byte b : byteData) {
            sb.append(Integer.toString((b & Constants.UNKNOWN) + 256, 16).substring(1));
        }
        return sb.toString();
    }

    @Override // android.app.Fragment
    public void onResume() {
        super.onResume();
        if (getActivity() != null) {
            Utils.sendAnalyticsScreen((RobloxServiceActivity) getActivity(), "AboutScreen");
        }
    }

    @Override // android.app.Fragment
    public void onDestroy() {
        super.onDestroy();
        mYear = 2000;
        mMonth = 0;
        mDay = 1;
        mDateSelected = false;
    }

    public static void onDateSet() {
        Calendar cal = new GregorianCalendar(mYear, mMonth, mDay);
        String date = DateUtils.formatDateTime(null, cal.getTimeInMillis(), 524304);
        mBirthday.setText(date);
        mDateSelected = true;
    }

    private void setImageViewValidation(ImageView v, Validation s) {
        switch (s) {
            case BLANK:
                v.setImageDrawable(null);
                break;
            case VALID:
                v.setImageResource(R.drawable.sign_up_checkmark);
                break;
            case INVALID:
                v.setImageResource(R.drawable.sign_up_xmark);
                break;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setUsernameValidation(Validation s) {
        setImageViewValidation(this.mUsernameCheckImageView, s);
        this.mUsernameValidation = s;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setPasswordValidation(Validation s) {
        setImageViewValidation(this.mPasswordCheckImageView, s);
        this.mPasswordValidation = s;
    }

    private void setPasswordVerifyValidation(Validation s) {
        setImageViewValidation(this.mPasswordVerifyCheckImageView, s);
        this.mPasswordVerifyValidation = s;
    }

    private void setEmailValidation(Validation s) {
        setImageViewValidation(this.mEmailCheckImageView, s);
        this.mEmailVerifyValidation = s;
    }

    public void onSignUpClicked() {
        getStringsFromUi();
        if (this.mUsername.equals("")) {
            Utils.alertExclusively(getActivity(), R.string.NoSignupUsernameError);
            return;
        }
        if (this.mUsername.equals("")) {
            Utils.alertExclusively(getActivity(), R.string.NoSignupPasswordError);
            return;
        }
        if (this.mGender != 1 && this.mGender != 2) {
            Utils.alertExclusively(getActivity(), R.string.NoSignupGenderError);
            return;
        }
        if (!mDateSelected) {
            Utils.alertExclusively(getActivity(), R.string.NoSignupBirthdayError);
            return;
        }
        doPasswordVerifyValidation(true);
        doEmailValidation();
        if (this.mUsernameValidation != Validation.VALID || this.mPasswordValidation != Validation.VALID || this.mPasswordVerifyValidation != Validation.VALID || this.mEmailVerifyValidation == Validation.INVALID) {
            Utils.alertExclusively(getActivity(), R.string.EntryIncorrectBody);
        } else {
            new SignUpAsyncTask().execute(new Void[0]);
        }
    }

    public void setNextButton(TextView view, final View nextView) {
        view.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: com.roblox.client.FragmentSignUp.12
            @Override // android.widget.TextView.OnEditorActionListener
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != 5) {
                    return false;
                }
                nextView.requestFocus();
                return true;
            }
        });
    }

    public static class DatePickerFragment extends DialogFragment implements DatePickerDialog.OnDateSetListener {
        @Override // android.app.DialogFragment
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new DatePickerDialog(getActivity(), this, FragmentSignUp.mYear, FragmentSignUp.mMonth, FragmentSignUp.mDay);
        }

        @Override // android.app.DatePickerDialog.OnDateSetListener
        public void onDateSet(DatePicker view, int year, int month, int day) {
            FragmentSignUp.mYear = year;
            FragmentSignUp.mMonth = month;
            FragmentSignUp.mDay = day;
            FragmentSignUp.onDateSet();
        }
    }

    public void getStringsFromUi() {
        this.mUsername = this.mUsernameEditText.getText().toString();
        this.mPassword = this.mPasswordEditText.getText().toString();
        this.mPasswordVerify = this.mPasswordVerifyEditText.getText().toString();
        this.mEmail = this.mEmailEditText.getText().toString();
    }

    public void doValidationTask(ValidationOp op) {
        getStringsFromUi();
        switch (op) {
            case USERNAME:
                if (this.mUsername.isEmpty()) {
                    setUsernameValidation(Validation.BLANK);
                } else if (this.mUsername.length() < 3) {
                    Utils.alertExclusively(getActivity(), R.string.UsernameTooShort);
                    setUsernameValidation(Validation.INVALID);
                } else if (this.mUsername.length() > 20) {
                    Utils.alertExclusively(getActivity(), R.string.UsernameTooLong);
                    setUsernameValidation(Validation.INVALID);
                } else {
                    new ValidationAsyncTask(op).execute(new Void[0]);
                }
                break;
            case PASSWORD:
                if (!this.mPassword.isEmpty()) {
                    new ValidationAsyncTask(op).execute(new Void[0]);
                } else {
                    setPasswordValidation(Validation.BLANK);
                }
                break;
        }
    }

    public void doPasswordVerifyValidation(boolean warn) {
        getStringsFromUi();
        if (this.mPassword.isEmpty() || this.mPasswordVerify.isEmpty()) {
            setPasswordVerifyValidation(Validation.BLANK);
            return;
        }
        if (this.mPassword.equals(this.mPasswordVerify)) {
            setPasswordVerifyValidation(Validation.VALID);
            return;
        }
        setPasswordVerifyValidation(Validation.INVALID);
        if (warn) {
            Utils.alertExclusively(getActivity(), R.string.VerifyPasswordWrong);
        }
    }

    public void doEmailValidation() {
        getStringsFromUi();
        if (this.mEmail.isEmpty()) {
            setEmailValidation(Validation.BLANK);
            return;
        }
        Pattern p = Pattern.compile("[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,4}");
        Matcher m = p.matcher(this.mEmail);
        boolean matches = m.matches();
        setEmailValidation(matches ? Validation.VALID : Validation.INVALID);
        if (!matches) {
            Utils.alertExclusively(getActivity(), R.string.EmailInvalid);
        }
    }

    class ValidationAsyncTask extends AsyncTask<Void, Void, Void> {
        ValidationOp mOp;
        String mPasswordLocal;
        String mUsernameLocal;
        String mResponse = null;
        JSONObject mJson = null;

        ValidationAsyncTask(ValidationOp op) {
            this.mUsernameLocal = null;
            this.mPasswordLocal = null;
            this.mOp = op;
            try {
                this.mUsernameLocal = URLEncoder.encode(FragmentSignUp.this.mUsername, "UTF-8");
                this.mPasswordLocal = URLEncoder.encode(FragmentSignUp.this.mPassword, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                Utils.alertUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "Username or Password not UTF-8 #1");
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... arg0) {
            switch (this.mOp) {
                case USERNAME:
                    this.mResponse = HttpAgent.readUrlToString(RobloxSettings.usernameCheckUrl(this.mUsernameLocal), null, null);
                    break;
                case PASSWORD:
                    this.mResponse = HttpAgent.readUrlToString(RobloxSettings.passwordCheckUrl(), RobloxSettings.passwordCheckUrlArgs(this.mUsernameLocal, this.mPasswordLocal), null);
                    break;
            }
            if (this.mResponse != null) {
                try {
                    this.mJson = new JSONObject(this.mResponse);
                } catch (JSONException e) {
                }
            }
            return null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            if (FragmentSignUp.this.getActivity() != null) {
                if (this.mResponse == null) {
                    if (!Utils.alertIfNetworkNotConnected((RobloxActivity) FragmentSignUp.this.getActivity())) {
                        Utils.sendUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "ValidationAsyncTask cannot get response");
                        Utils.alertExclusively((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.ConnectionError);
                        return;
                    }
                    return;
                }
                if (this.mJson == null) {
                    Utils.alertUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "ValidationAsyncTask cannot parse JSON #1");
                    return;
                }
                try {
                    switch (this.mOp) {
                        case USERNAME:
                            if (this.mJson.getInt("data") == 0) {
                                FragmentSignUp.this.setUsernameValidation(Validation.VALID);
                            } else {
                                FragmentSignUp.this.new UsernameSuggestionAsyncTask().execute(new Void[0]);
                                FragmentSignUp.this.setUsernameValidation(Validation.INVALID);
                            }
                            break;
                        case PASSWORD:
                            if (this.mJson.getBoolean("success")) {
                                FragmentSignUp.this.setPasswordValidation(Validation.VALID);
                                FragmentSignUp.this.doPasswordVerifyValidation(false);
                            } else {
                                Utils.alertExclusively(FragmentSignUp.this.getActivity(), R.string.PasswordWrongAndroid);
                                FragmentSignUp.this.setPasswordValidation(Validation.INVALID);
                            }
                            break;
                    }
                } catch (JSONException e) {
                    Utils.alertUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "ValidationAsyncTask cannot parse JSON #2");
                }
            }
        }
    }

    class UsernameSuggestionAsyncTask extends AsyncTask<Void, Void, Void> {
        String mResponse = null;
        String mUsernameLocal;

        UsernameSuggestionAsyncTask() {
            this.mUsernameLocal = null;
            try {
                this.mUsernameLocal = URLEncoder.encode(FragmentSignUp.this.mUsername, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                Utils.alertUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "Username not UTF-8");
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... arg0) {
            this.mResponse = HttpAgent.readUrlToString(RobloxSettings.recommendUsernameUrl(this.mUsernameLocal), null, null);
            return null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            if (FragmentSignUp.this.getActivity() != null) {
                if (this.mResponse == null) {
                    if (!Utils.alertIfNetworkNotConnected((RobloxActivity) FragmentSignUp.this.getActivity())) {
                        Utils.sendUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "UsernameSuggestionAsyncTask cannot get response");
                        Utils.alertExclusively((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.ConnectionError);
                        return;
                    }
                    return;
                }
                if (this.mUsernameLocal.equals(this.mResponse)) {
                    Utils.alertExclusively(FragmentSignUp.this.getActivity(), R.string.UsernameExplicit);
                    return;
                }
                String format = FragmentSignUp.this.getResources().getString(R.string.UsernameTaken);
                String s = Utils.format(format, this.mResponse);
                Utils.alertExclusively(FragmentSignUp.this.getActivity(), s);
            }
        }
    }

    class SignUpAsyncTask extends AsyncTask<Void, Void, Void> {
        AlertDialog mAlertDialog;
        boolean mCancel;
        int mDayLocal;
        String mEmailLocal;
        int mGenderLocal;
        int mMonthLocal;
        String mPasswordLocal;
        String mStatus;
        String mUsernameLocal;
        int mYearLocal;
        String mResponse = null;
        JSONObject mJson = null;

        SignUpAsyncTask() {
            this.mUsernameLocal = null;
            this.mPasswordLocal = null;
            this.mGenderLocal = 0;
            this.mYearLocal = 2000;
            this.mMonthLocal = 0;
            this.mDayLocal = 1;
            this.mEmailLocal = null;
            this.mAlertDialog = null;
            this.mCancel = false;
            this.mUsernameLocal = null;
            this.mPasswordLocal = null;
            this.mGenderLocal = FragmentSignUp.this.mGender;
            this.mYearLocal = FragmentSignUp.mYear;
            this.mMonthLocal = FragmentSignUp.mMonth;
            this.mDayLocal = FragmentSignUp.mDay;
            this.mEmailLocal = FragmentSignUp.this.mEmail;
            this.mAlertDialog = Utils.alert((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.SigningUp);
            this.mAlertDialog.setCanceledOnTouchOutside(false);
            this.mAlertDialog.setCancelable(false);
            try {
                this.mUsernameLocal = URLEncoder.encode(FragmentSignUp.this.mUsername, "UTF-8");
                this.mPasswordLocal = URLEncoder.encode(FragmentSignUp.this.mPassword, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                Utils.alertUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "Username or Password not UTF-8 #1");
                this.mCancel = true;
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... arg0) {
            if (!this.mCancel) {
                String gender = this.mGenderLocal == 1 ? "Male" : "Female";
                String dateOfBirth = Utils.format("%d/%d/%d", Integer.valueOf(this.mMonthLocal + 1), Integer.valueOf(this.mDayLocal), Integer.valueOf(this.mYearLocal));
                HttpAgent.HttpHeader[] headerList = new HttpAgent.HttpHeader[1];
                try {
                    String s = RobloxSettings.isTestSite() ? FragmentSignUp.this.s6 + FragmentSignUp.this.s7 + FragmentSignUp.this.s8 + FragmentSignUp.this.s9 + FragmentSignUp.this.s10 + this.mUsernameLocal : FragmentSignUp.this.s1 + FragmentSignUp.this.s2 + FragmentSignUp.this.s3 + FragmentSignUp.this.s4 + FragmentSignUp.this.s5 + this.mUsernameLocal;
                    String h = FragmentSignUp.this.computeHash(s);
                    HttpAgent.HttpHeader header = new HttpAgent.HttpHeader();
                    header.header = "X-RBXUSER-TOKEN";
                    header.value = h;
                    headerList[0] = header;
                } catch (Exception e) {
                }
                this.mResponse = HttpAgent.readUrlToString(RobloxSettings.signUpUrl(), RobloxSettings.signUpUrlArgs(this.mUsernameLocal, this.mPasswordLocal, gender, dateOfBirth), headerList);
                if (this.mResponse != null) {
                    try {
                        this.mJson = new JSONObject(this.mResponse);
                        this.mStatus = this.mJson.getString("Status");
                    } catch (JSONException e2) {
                    }
                }
            }
            return null;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public void onPostExecute(Void result) {
            super.onPostExecute(result);
            if (FragmentSignUp.this.getActivity() != null) {
                this.mAlertDialog.cancel();
                this.mAlertDialog = null;
                if (this.mResponse == null) {
                    if (!Utils.alertIfNetworkNotConnected((RobloxActivity) FragmentSignUp.this.getActivity())) {
                        Utils.sendUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "SignUpAsyncTask cannot get response");
                        Utils.alertExclusively((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.ConnectionError);
                        return;
                    }
                    return;
                }
                if (this.mJson == null || this.mStatus == null) {
                    Utils.alertUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), "SignUpAsyncTask cannot parse JSON.");
                    return;
                }
                if (this.mStatus.equals("OK")) {
                    if (FragmentSignUp.this.mSignUpCallbacks != null) {
                        FragmentSignUp.this.mSignUpCallbacks.onFragmentSignUpOk(this.mJson);
                    }
                } else {
                    if (this.mStatus.equals("Already Taken")) {
                        Utils.alertExclusively((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.UsernameTaken);
                        return;
                    }
                    if (this.mStatus.equals("Invalid Characters Used")) {
                        Utils.alertExclusively((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.InvalidCharactersUsed);
                        return;
                    }
                    if (this.mStatus.equals("Username Cannot Contain Spaces")) {
                        Utils.alertExclusively((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.UsernameCannotContainSpaces);
                    } else if (this.mStatus.equals("AccountCreationFloodcheck")) {
                        Utils.alertExclusively((RobloxActivity) FragmentSignUp.this.getActivity(), R.string.AccountCreationFloodcheck);
                    } else {
                        Utils.alertUnexpectedError((RobloxActivity) FragmentSignUp.this.getActivity(), this.mStatus);
                    }
                }
            }
        }
    }
}
