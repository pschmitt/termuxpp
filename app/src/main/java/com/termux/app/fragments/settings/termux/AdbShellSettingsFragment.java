package com.termux.app.fragments.settings.termux;

import android.app.Activity;
import android.view.WindowManager;

import androidx.annotation.Keep;

import com.termux.app.settings.GroupedPreferencesFragment;
import androidx.annotation.StringRes;

/**
 * Base for the ADB shell authorization screens. Client names and certificate fingerprints stay out of
 * screenshots and the recents thumbnail while one of these screens is visible, like the enrollment approval.
 */
@Keep
abstract class AdbShellSettingsFragment extends GroupedPreferencesFragment {
    private static int secureScreens;
    private static final int[] TITLES = {
        com.termux.R.string.adb_shell_authorizations_title,
        com.termux.R.string.adb_shell_client_title,
    };

    private CharSequence previousTitle;
    private boolean secured;

    @StringRes
    abstract int titleResource();

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = getActivity();
        if (activity == null) return;
        CharSequence current = activity.getTitle();
        if (!isAdbShellTitle(activity, current)) previousTitle = current;
        activity.setTitle(titleResource());
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        secured = true;
        secureScreens++;
    }

    @Override
    public void onStop() {
        Activity activity = getActivity();
        if (activity != null) {
            if (previousTitle != null && activity.getTitle() != null &&
                activity.getTitle().toString().equals(getString(titleResource()))) {
                activity.setTitle(previousTitle);
            }
            if (secured && --secureScreens <= 0) {
                secureScreens = 0;
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        }
        secured = false;
        super.onStop();
    }

    private boolean isAdbShellTitle(Activity activity, CharSequence title) {
        if (title == null) return false;
        for (int resource : TITLES) {
            if (title.toString().equals(activity.getString(resource))) return true;
        }
        return false;
    }
}
