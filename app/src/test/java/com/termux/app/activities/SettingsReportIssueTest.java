package com.termux.app.activities;

import android.content.Intent;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.fragments.settings.AboutPreferencesFragment;
import com.termux.app.fragments.settings.AppDetailsFragment;
import com.termux.app.terminal.ReportIssueRequest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
public class SettingsReportIssueTest {

    private static PreferenceFragmentCompat rootFragment(SettingsActivity activity) {
        Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        Assert.assertTrue(fragment instanceof PreferenceFragmentCompat);
        return (PreferenceFragmentCompat) fragment;
    }

    @Test
    public void reportIssueIsProminentAtTopOfAboutPage() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = rootFragment(activity);

        Assert.assertNull(root.findPreference("report_issue"));
        Assert.assertNotNull("Updates is still there", root.findPreference("updates"));
        Assert.assertNotNull(root.findPreference("about"));

        AboutPreferencesFragment about = new AboutPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, about).commitNow();
        Preference reportIssue = about.findPreference("report_issue");
        Assert.assertNotNull(reportIssue);
        Assert.assertTrue(reportIssue.isVisible());
        Assert.assertEquals(activity.getString(R.string.action_report_issue), reportIssue.getTitle().toString());
        Assert.assertSame(reportIssue, about.getPreferenceScreen().getPreference(0));
    }

    @Test
    public void appDetailsOpenInsideSettingsAndBackReturnsToAbout() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = rootFragment(activity);
        root.findPreference("about").performClick();
        activity.getSupportFragmentManager().executePendingTransactions();

        PreferenceFragmentCompat about = rootFragment(activity);
        about.findPreference("about_information").performClick();
        activity.getSupportFragmentManager().executePendingTransactions();

        Fragment details = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        Assert.assertTrue(details instanceof AppDetailsFragment);
        Assert.assertEquals(activity.getString(R.string.about_information_action_title), activity.getTitle());
        Assert.assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        Assert.assertTrue(activity.getSupportFragmentManager().popBackStackImmediate());
        Assert.assertSame(about, rootFragment(activity));
    }

    @Test
    public void clickingReportIssueAsksTheTerminalToRunTheExistingFlow() {
        ReportIssueRequest.consume();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        AboutPreferencesFragment about = new AboutPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, about).commitNow();
        Preference reportIssue = about.findPreference("report_issue");
        Assert.assertNotNull(reportIssue);

        Assert.assertTrue(reportIssue.getOnPreferenceClickListener().onPreferenceClick(reportIssue));

        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        Assert.assertNotNull(started);
        Assert.assertEquals(TermuxActivity.class.getName(), started.getComponent().getClassName());
        Assert.assertNotEquals(0, started.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // The terminal gets exactly one request, and it is the one that runs the consent dialog first.
        Assert.assertTrue(ReportIssueRequest.consume());
        Assert.assertFalse(ReportIssueRequest.consume());
    }

}
