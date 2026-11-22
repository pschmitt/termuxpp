package com.termux.app.fragments.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.settings.GroupedPreferencesFragment;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.ReportIssueRequest;
import dev.pschmitt.termux.shared.android.PackageUtils;
import dev.pschmitt.termux.shared.interact.ShareUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.TermuxUtils;

@Keep
public class AboutPreferencesFragment extends GroupedPreferencesFragment {

    @Nullable private CharSequence previousTitle;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.about_preferences, rootKey);
        Preference reportIssue = findPreference("report_issue");
        if (reportIssue != null) {
            reportIssue.setOnPreferenceClickListener(preference -> {
                ReportIssueRequest.request();
                Intent intent = TermuxActivity.newInstance(context);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(intent);
                return true;
            });
        }

        configureDonatePreference(context);
    }

    private void configureDonatePreference(@NonNull Context context) {
        Preference donatePreference = findPreference("donate");
        if (donatePreference != null) {
            String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
            if (signingCertificateSHA256Digest != null) {
                // If APK is a Google Playstore release, then do not show the donation link
                // since Termux isn't exempted from the playstore policy donation links restriction
                // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                String apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest);
                if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                    donatePreference.setVisible(false);
                    return;
                } else {
                    donatePreference.setVisible(true);
                }
            }

            donatePreference.setOnPreferenceClickListener(preference -> {
                ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL);
                return true;
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = getActivity();
        if (activity != null) {
            previousTitle = activity.getTitle();
            activity.setTitle(R.string.about_preference_title);
        }
    }

    @Override
    public void onStop() {
        Activity activity = getActivity();
        if (activity != null && previousTitle != null) activity.setTitle(previousTitle);
        super.onStop();
    }
}
