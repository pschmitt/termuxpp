package com.termux.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.termux.R;
import com.termux.app.AdbShellApprovalActivity;
import com.termux.app.AdbShellService;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.termux.AdbShellAuthorizationPreferencesFragment;
import com.termux.app.fragments.settings.termux.AdbShellAuthorizationsPreferencesFragment;
import com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.xmlpull.v1.XmlPullParser;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AdbShellAuthorizationsPreferencesFragmentTest {
    private static final String TOKEN = "enrollment-token-that-must-never-be-displayed";
    private static final String FIRST = String.format(Locale.ROOT, "%064x", 0xa1);
    private static final String SECOND = String.format(Locale.ROOT, "%064x", 0xb2);

    private Application application;
    private SettingsActivity activity;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("adb_shell_authorizations", 0).edit().clear().commit();
        application.getSharedPreferences(AdbShellService.PREFS_NAME, 0).edit().clear().commit();
        activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    /** Stands in for a parent settings screen: one row that links to the authorizations screen. */
    public static class NavigationRoot extends PreferenceFragmentCompat {
        @Override public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            Preference row = new Preference(requireContext());
            row.setKey("row");
            row.setFragment(AdbShellAuthorizationsPreferencesFragment.class.getName());
            screen.addPreference(row);
            setPreferenceScreen(screen);
        }
    }

    private SharedPreferences authorizations() {
        return application.getSharedPreferences("adb_shell_authorizations", 0);
    }

    private SharedPreferences adbShell() {
        return application.getSharedPreferences(AdbShellService.PREFS_NAME, 0);
    }

    private <T extends Fragment> T show(T fragment, boolean addToBackStack) {
        androidx.fragment.app.FragmentTransaction transaction = activity.getSupportFragmentManager()
            .beginTransaction().replace(R.id.settings, fragment);
        if (addToBackStack) transaction.addToBackStack(null);
        transaction.commit();
        activity.getSupportFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        return fragment;
    }

    private static AdbShellAuthorizationPreferencesFragment detail(String fingerprint) {
        AdbShellAuthorizationPreferencesFragment fragment = new AdbShellAuthorizationPreferencesFragment();
        Bundle arguments = new Bundle();
        arguments.putString(AdbShellAuthorizationsPreferencesFragment.ARG_FINGERPRINT, fingerprint);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Test
    public void networkRowsOpenDialogsAndSetupRowIsGone() {
        DebuggingPreferencesFragment debugging = show(new DebuggingPreferencesFragment(), false);
        assertNull(debugging.findPreference("adb_shell_setup"));

        debugging.findPreference("adb_shell_current_network").performClick();
        ShadowLooper.idleMainLooper();
        Dialog network = ShadowDialog.getLatestDialog();
        assertNotNull(network);
        assertTrue(network.isShowing());
        network.dismiss();

        debugging.findPreference("adb_shell_allowed_ssids").performClick();
        ShadowLooper.idleMainLooper();
        Dialog allowed = ShadowDialog.getLatestDialog();
        assertNotNull(allowed);
        assertTrue(allowed.isShowing());
        assertTrue(allowed != network);
        allowed.dismiss();
    }

    @Test
    public void debuggingRowLinksToTheDedicatedScreenInsteadOfStartingADialog() throws Exception {
        String auto = "http://schemas.android.com/apk/res-auto";
        boolean found = false;
        try (XmlResourceParser xml = activity.getResources().getXml(R.xml.termux_debugging_preferences)) {
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.getEventType() != XmlPullParser.START_TAG
                    || !"adb_shell_authorizations".equals(xml.getAttributeValue(auto, "key"))) continue;
                found = true;
                assertEquals(AdbShellAuthorizationsPreferencesFragment.class.getName(),
                    xml.getAttributeValue(auto, "fragment"));
                assertEquals(R.string.adb_shell_authorizations_title,
                    xml.getAttributeResourceValue(auto, "title", 0));
                assertEquals(R.string.adb_shell_authorizations_summary,
                    xml.getAttributeResourceValue(auto, "summary", 0));
                assertFalse(xml.getAttributeBooleanValue(auto, "persistent", true));
            }
        }
        assertTrue(found);
        assertEquals("ADB shell authorizations", activity.getString(R.string.adb_shell_authorizations_title));
        assertEquals("Review and revoke individual clients", activity.getString(R.string.adb_shell_authorizations_summary));
        assertTrue(Fragment.class.isAssignableFrom(Class.forName(AdbShellAuthorizationsPreferencesFragment.class.getName())));
    }

    @Test
    public void listIsReachedByNormalPreferenceNavigationAndShowsItsTitle() {
        PreferenceFragmentCompat root = show(new NavigationRoot(), false);
        root.findPreference("row").performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.settings)
            instanceof AdbShellAuthorizationsPreferencesFragment);
        assertNull("no dialog may be used to manage authorizations", ShadowDialog.getLatestDialog());
        assertEquals("ADB shell authorizations", activity.getTitle().toString());
    }

    @Test
    public void emptyScreenShowsAccessInformationAndNoClients() {
        AdbShellAuthorizationsPreferencesFragment screen = show(new AdbShellAuthorizationsPreferencesFragment(), false);
        assertEquals(activity.getString(R.string.adb_shell_status_disabled),
            screen.findPreference("adb_shell_status").getSummary().toString());
        String connection = screen.findPreference("adb_shell_connection").getSummary().toString();
        assertTrue(connection, connection.contains(String.valueOf(AdbShellService.PORT)));
        String enrollment = screen.findPreference("adb_shell_enrollment").getSummary().toString();
        assertTrue(enrollment, enrollment.contains("fingerprint"));
        assertTrue(enrollment, enrollment.contains("PIN and face unlock are not accepted"));

        PreferenceCategory clients = screen.findPreference("adb_shell_clients");
        assertEquals(1, clients.getPreferenceCount());
        Preference none = clients.getPreference(0);
        assertEquals("No authorized clients.", none.getTitle().toString());
        assertFalse(none.isSelectable());
    }

    @Test
    public void enabledAccessIsReported() {
        adbShell().edit().putBoolean(AdbShellService.PREF_ENABLED, true).commit();
        AdbShellAuthorizationsPreferencesFragment screen = show(new AdbShellAuthorizationsPreferencesFragment(), false);
        assertEquals(activity.getString(R.string.adb_shell_status_enabled),
            screen.findPreference("adb_shell_status").getSummary().toString());
    }

    @Test
    public void authorizedClientsAreListedAndOpenTheirDetails() {
        authorizations().edit().putString(FIRST, "workstation").putString(SECOND, "laptop").commit();
        AdbShellAuthorizationsPreferencesFragment screen = show(new AdbShellAuthorizationsPreferencesFragment(), false);
        PreferenceCategory clients = screen.findPreference("adb_shell_clients");
        assertEquals(2, clients.getPreferenceCount());
        Preference first = clients.getPreference(0);
        assertEquals("workstation", first.getTitle().toString());
        assertEquals(FIRST, first.getSummary().toString());
        assertEquals("laptop", clients.getPreference(1).getTitle().toString());
        assertEquals(AdbShellAuthorizationPreferencesFragment.class.getName(), first.getFragment());

        first.performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        Fragment current = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        assertTrue(current instanceof AdbShellAuthorizationPreferencesFragment);
        PreferenceFragmentCompat details = (PreferenceFragmentCompat) current;
        assertEquals("workstation", details.findPreference("adb_shell_client_name").getSummary().toString());
        assertEquals(FIRST, details.findPreference("adb_shell_client_fingerprint").getSummary().toString());
        assertNotNull(details.findPreference("adb_shell_client_revoke"));
        assertEquals("Authorized client", activity.getTitle().toString());
        assertNull("details are a screen, not a dialog", ShadowDialog.getLatestDialog());
    }

    @Test
    public void revokingRequiresConfirmationAndReturnsToTheList() {
        authorizations().edit().putString(FIRST, "workstation").putString(SECOND, "laptop").commit();
        adbShell().edit().putBoolean(AdbShellService.PREF_ENABLED, true).commit();
        show(new AdbShellAuthorizationsPreferencesFragment(), false);
        AdbShellAuthorizationPreferencesFragment details = show(detail(FIRST), true);

        Preference revoke = details.findPreference("adb_shell_client_revoke");
        revoke.performClick();
        ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog instanceof AlertDialog);
        assertTrue(authorizations().contains(FIRST));
        assertNull(shadowOf(application).getNextStartedService());

        // Cancelling keeps the authorization.
        ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(authorizations().contains(FIRST));
        assertNull(shadowOf(application).getNextStartedService());

        revoke.performClick();
        ShadowLooper.idleMainLooper();
        dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        assertFalse(authorizations().contains(FIRST));
        assertTrue(authorizations().contains(SECOND));
        Intent started = shadowOf(application).getNextStartedService();
        assertNotNull(started);
        assertEquals(AdbShellService.ACTION_AUTHORIZATIONS_CHANGED, started.getAction());
        assertEquals(AdbShellService.class.getName(), started.getComponent().getClassName());

        Fragment current = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        assertTrue(current instanceof AdbShellAuthorizationsPreferencesFragment);
        PreferenceCategory clients = ((AdbShellAuthorizationsPreferencesFragment) current)
            .findPreference("adb_shell_clients");
        assertEquals(1, clients.getPreferenceCount());
        assertEquals("laptop", clients.getPreference(0).getTitle().toString());
    }

    @Test
    public void revokingWhileAccessIsOffDoesNotStartTheService() {
        authorizations().edit().putString(FIRST, "workstation").commit();
        AdbShellAuthorizationPreferencesFragment details = show(detail(FIRST), false);
        details.findPreference("adb_shell_client_revoke").performClick();
        ShadowLooper.idleMainLooper();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // Dialog buttons deliver their click through a handler message.
        ShadowLooper.idleMainLooper();
        assertFalse(authorizations().contains(FIRST));
        assertNull(shadowOf(application).getNextStartedService());
    }

    @Test
    public void staleDetailsOfARevokedClientGoBackWithoutActions() {
        authorizations().edit().putString(SECOND, "laptop").commit();
        show(new AdbShellAuthorizationsPreferencesFragment(), false);
        AdbShellAuthorizationPreferencesFragment details = show(detail(FIRST), true);
        activity.getSupportFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        assertEquals(0, details.getPreferenceScreen().getPreferenceCount());
        assertEquals(0, activity.getSupportFragmentManager().getBackStackEntryCount());
        assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.settings)
            instanceof AdbShellAuthorizationsPreferencesFragment);
    }

    @Test
    public void enrollmentTokenIsNeverDisplayed() {
        adbShell().edit().putBoolean(AdbShellService.PREF_ENABLED, true)
            .putString(AdbShellService.PREF_TOKEN, TOKEN).commit();
        authorizations().edit().putString(FIRST, "workstation").commit();
        AdbShellAuthorizationsPreferencesFragment list = show(new AdbShellAuthorizationsPreferencesFragment(), false);
        assertNoText(list.getPreferenceScreen());
        AdbShellAuthorizationPreferencesFragment details = show(detail(FIRST), true);
        assertNoText(details.getPreferenceScreen());
        assertEquals(TOKEN, adbShell().getString(AdbShellService.PREF_TOKEN, null));
    }

    private static void assertNoText(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            assertFalse(String.valueOf(preference.getTitle()), String.valueOf(preference.getTitle()).contains(TOKEN));
            assertFalse(String.valueOf(preference.getSummary()), String.valueOf(preference.getSummary()).contains(TOKEN));
            if (preference instanceof PreferenceGroup) assertNoText((PreferenceGroup) preference);
        }
    }

    @Test
    public void screensAreExcludedFromScreenshotsWhileVisible() {
        assertFalse(isSecure());
        show(new AdbShellAuthorizationsPreferencesFragment(), false);
        assertTrue(isSecure());
        authorizations().edit().putString(FIRST, "workstation").commit();
        show(detail(FIRST), true);
        assertTrue(isSecure());
        activity.getSupportFragmentManager().popBackStackImmediate();
        assertTrue(isSecure());
        show(new Fragment(), false);
        assertFalse(isSecure());
    }

    private boolean isSecure() {
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    @Test
    public void managementIntentNoLongerOpensADialogInTheApprovalActivity() {
        AdbShellApprovalActivity approval = Robolectric.buildActivity(AdbShellApprovalActivity.class,
            new Intent().setAction("manage")).create().get();
        assertTrue(approval.isFinishing());
        assertNull(ShadowDialog.getLatestDialog());
    }
}
