package com.termux.app;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.settings.AppAccent;
import com.termux.app.terminal.ProcessInfo;
import com.termux.app.terminal.ReportIssueRequest;
import com.termux.app.terminal.TerminalContextMenu;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxContextMenuSheet;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TmuxProgramIcon;
import com.termux.app.terminal.io.ExtraKeysSwipeDetector;
import com.termux.app.terminal.io.ExtraKeysSwipeViewPager;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import dev.pschmitt.termux.shared.activities.ReportActivity;
import dev.pschmitt.termux.shared.activity.ActivityUtils;
import dev.pschmitt.termux.shared.activity.media.AppCompatActivityUtils;
import dev.pschmitt.termux.shared.data.IntentUtils;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.android.PermissionUtils;
import dev.pschmitt.termux.shared.data.DataUtils;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.SettingsActivity;
import dev.pschmitt.termux.shared.termux.crash.TermuxCrashUtils;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.app.terminal.SessionsListView;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;
import dev.pschmitt.termux.shared.termux.interact.TextInputDialogUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxUtils;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import dev.pschmitt.termux.shared.termux.theme.TermuxThemeUtils;
import dev.pschmitt.termux.shared.theme.NightMode;
import dev.pschmitt.termux.shared.view.KeyboardUtils;
import dev.pschmitt.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;


    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    /** The terminal context menu bottom sheet while it is showing. */
    private android.app.Dialog mContextSheetDialog;

    private AlertDialog sessionSwitcher;
    private AlertDialog tmuxSwitcher;
    private final Set<String> collapsedTmuxSessions = new HashSet<>();
    @Nullable private TerminalSession tmuxSidebarSession;
    private boolean tmuxSidebarMode;
    private boolean restoreKeyboardAfterSidebar;
    private String lastTmuxSidebarLayout;
    private String appAccent;
    private static boolean pendingPropertiesReload;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        appAccent = AppAccent.get(this);
        AppAccent.apply(this, appAccent);
        setContentView(R.layout.activity_termux);
        configureSessionShelf();

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        registerForContextMenu(mTerminalView);
        mTerminalView.setContextMenuPresenter(view -> showTerminalContextSheet());

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;
        if (!AppAccent.get(this).equals(appAccent)) {
            recreate();
            return;
        }
        if (pendingPropertiesReload) {
            reloadActivityStyling(true);
            return;
        }

        // Returning from a permission prompt or settings page: let the guest see the result.
        if (mTermuxService != null) mTermuxService.refreshPermissionState();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        // Settings > Report issue brings the terminal back and asks it to run the usual Report issue flow, which
        // reports the current session. It asks for consent first and the request only exists for a short time.
        if (mTermuxTerminalViewClient != null && getCurrentSession() != null && ReportIssueRequest.consume())
            mTermuxTerminalViewClient.reportIssueFromTranscript();

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        if (tmuxSwitcher != null) tmuxSwitcher.dismiss();
        if (mContextSheetDialog != null) mContextSheetDialog.dismiss();
        if (mTermuxSessionListViewController != null) mTermuxSessionListViewController.dismissDialogs();
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity, but only
            // ours: a newer activity may already have connected (recreation, relaunch).
            mTermuxService.unsetTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;
        mTermuxTerminalSessionActivityClient.applyPaletteToSessions();

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();
        pendingPropertiesReload = false;

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
        if (termuxSessionsListView instanceof SessionsListView)
            ((SessionsListView) termuxSessionsListView).setReorderListener(mTermuxSessionListViewController);
        updateTmuxSidebar();
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));

        // The actions of the swipes on the extra keys row. They are read on each swipe, so that a
        // reloaded termux.properties applies at once.
        if (terminalToolbarViewPager instanceof ExtraKeysSwipeViewPager) {
            ((ExtraKeysSwipeViewPager) terminalToolbarViewPager).setListener(new ExtraKeysSwipeViewPager.Listener() {
                private TerminalGestureAction action(ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) {
                    if (mProperties == null) return TerminalGestureAction.NONE;
                    return mProperties.getExtraKeysSwipeAction(direction == ExtraKeysSwipeDetector.Direction.RIGHT, longSwipe);
                }

                @Override
                public boolean hasSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction) {
                    // With no action configured the swipe is left alone: no animation, nothing consumed.
                    return action(direction, false) != TerminalGestureAction.NONE;
                }

                @Override
                public boolean hasLongSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction) {
                    return action(direction, true) != TerminalGestureAction.NONE;
                }

                @Nullable
                @Override
                public String getSwipeLabel(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) {
                    TerminalGestureAction action = action(direction, longSwipe);
                    return action == TerminalGestureAction.NONE ? null : action.getLabel();
                }

                @Override
                public void onSwipePreview(@Nullable String label, boolean armed) {
                    if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.previewGesture(label, armed);
                }

                @Override
                public boolean isFeedbackEnabled() {
                    return mProperties != null && mProperties.areGestureAnimationsEnabled();
                }

                @Override
                public void onSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) {
                    if (mTermuxTerminalViewClient == null || mProperties == null) return;
                    boolean right = direction == ExtraKeysSwipeDetector.Direction.RIGHT;
                    TerminalGestureAction action = mProperties.getExtraKeysSwipeAction(right, longSwipe);
                    if (action == TerminalGestureAction.NONE) return;
                    mTermuxTerminalViewClient.showGestureFeedback(action.getLabel());
                    mTermuxTerminalViewClient.performGestureAction(action,
                        mProperties.getExtraKeysSwipeKeys(right, longSwipe), mProperties.getExtraKeysSwipeCommand(right, longSwipe),
                        mProperties.getGestureCommandContext());
                }
            });
        }
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            restoreKeyboardAfterSidebar = false;
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerVisible(Gravity.END)) {
            getDrawer().closeDrawer(Gravity.END);
            return;
        }
        if (getDrawer().isDrawerOpen(Gravity.START)) {
            if (tmuxSidebarSession != null) {
                tmuxSidebarSession = null;
                updateTmuxSidebar();
                return;
            }
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(mProperties.getTerminalToastGravity(), 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        TerminalContextMenu.populateMenu(menu, getResources(), getContextMenuState(currentSession));
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    private TerminalContextMenu.State getContextMenuState(@NonNull TerminalSession session) {
        return new TerminalContextMenu.State(mTerminalView.isAutoFillEnabled(),
            !DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()),
            session.getPid(), session.isRunning(), mPreferences.shouldKeepScreenOn(), isWakeLockHeld(),
            null, null, null, 0, 0, -1);
    }

    private boolean isWakeLockHeld() {
        return mTermuxService != null && mTermuxService.isWakeLockHeld();
    }

    /** The state for the sheet, which also shows which session it acts on and how many URLs it found. */
    private TerminalContextMenu.State getContextSheetState(@NonNull TerminalSession session) {
        int pid = session.isRunning() ? session.getPid() : -1;
        String cwd = ProcessInfo.readCwd(pid);
        if (cwd != null) cwd = ProcessInfo.displayCwd(cwd, TermuxNix.RUNTIME_DIR_PATH + "/root");
        int number = 0;
        int count = 0;
        if (mTermuxService != null) {
            number = mTermuxService.getIndexOfSession(session) + 1;
            count = mTermuxService.getTermuxSessionsSize();
        }
        Map<String, String> urls = mTermuxTerminalViewClient.collectUrlItems(session);
        return new TerminalContextMenu.State(mTerminalView.isAutoFillEnabled(),
            !DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()),
            session.getPid(), session.isRunning(), mPreferences.shouldKeepScreenOn(), isWakeLockHeld(),
            session.mSessionName, ProcessInfo.readName(pid), cwd, number, count,
            urls == null ? -1 : urls.size());
    }

    /**
     * Present the terminal context menu as a grouped bottom sheet. Falls back to the system
     * context menu (see {@link #onCreateContextMenu}) when there is no session or the sheet fails.
     */
    private boolean showTerminalContextSheet() {
        if (mContextSheetDialog != null && mContextSheetDialog.isShowing()) return true;
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null || isFinishing() || isDestroyed()) return false;

        TerminalContextMenu.State state = getContextSheetState(currentSession);
        return showContextSheet(TerminalContextMenu.populateSheet(new TermuxContextMenuSheet(this), getResources(), state),
            state);
    }

    /**
     * Show a sheet of the terminal menu. A tile that opens a sheet of choices (Share, Autofill) shows that sheet
     * once this one is gone, and the stored selected text is only released when the last sheet closes.
     */
    private boolean showContextSheet(@NonNull TermuxContextMenuSheet sheet, @NonNull TerminalContextMenu.State state) {
        try {
            mContextSheetDialog = sheet.show(chosenItemId -> {
                mContextSheetDialog = null;
                if (chosenItemId == TerminalContextMenu.SHARE_MENU || chosenItemId == TerminalContextMenu.AUTOFILL_MENU) {
                    TermuxContextMenuSheet choices = TerminalContextMenu.populateChoiceSheet(chosenItemId,
                        new TermuxContextMenuSheet(this), getResources(), state,
                        mTerminalView.getStoredSelectedText(), getCurrentTranscript());
                    if (choices != null && !isFinishing() && !isDestroyed() && showContextSheet(choices, state)) return;
                }
                // Same order as the system menu: run the chosen action, then release the stored selected text.
                if (chosenItemId >= 0) handleContextMenuAction(chosenItemId);
                mTerminalView.onContextMenuClosed(null);
            });
            return true;
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show context menu sheet", e);
            return false;
        }
    }

    /** The transcript of the current session, or null if there is none. */
    @Nullable
    private String getCurrentTranscript() {
        TerminalSession session = getCurrentSession();
        if (session == null || session.getEmulator() == null) return null;
        return session.getEmulator().getScreen().getTranscriptText();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (handleContextMenuAction(item.getItemId())) return true;
        return super.onContextItemSelected(item);
    }

    private boolean handleContextMenuAction(int itemId) {
        TerminalSession session = getCurrentSession();

        switch (itemId) {
            case TerminalContextMenu.SELECT_URL:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case TerminalContextMenu.SHARE_TRANSCRIPT:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case TerminalContextMenu.SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case TerminalContextMenu.AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case TerminalContextMenu.AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case TerminalContextMenu.RESET_TERMINAL:
                onResetTerminalSession(session);
                return true;
            case TerminalContextMenu.KILL_PROCESS:
                showKillSessionDialog(session);
                return true;
            // Only reached if the sheet of choices could not be shown: do the most common choice.
            case TerminalContextMenu.SHARE_MENU:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case TerminalContextMenu.AUTOFILL_MENU:
                mTerminalView.requestAutoFillPassword();
                return true;
            case TerminalContextMenu.KILL_PROCESS_NOW:
                if (session != null) session.finishIfRunning();
                return true;
            case TerminalContextMenu.TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case TerminalContextMenu.TOGGLE_WAKE_LOCK:
                if (mTermuxService != null) mTermuxService.toggleWakeLock();
                return true;
            case TerminalContextMenu.SETTINGS:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        DialogUtils.builder(this, R.drawable.ui_ic_delete, true)
            .setTitle(R.string.title_confirm_kill_process)
            .setMessage(R.string.msg_confirm_kill_process)
            .setPositiveButton(R.string.action_kill_session, (dialog, id) -> {
                dialog.dismiss();
                session.finishIfRunning();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(dev.pschmitt.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(dev.pschmitt.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public boolean openTerminalUi(String action, @Nullable String query) {
        if (!mIsVisible || isFinishing() || isDestroyed()) return false;
        switch (action) {
            case "more":
                return mTerminalView.showContextMenu();
            case "settings":
                Intent intent = new Intent(this, SettingsActivity.class);
                if (query != null) intent.putExtra(SettingsActivity.EXTRA_SEARCH_QUERY, query);
                return ActivityUtils.startActivity(this, intent) == null;
            case "tmux-switcher":
                return showTmuxSwitcher(getCurrentSession());
            case "session-switcher":
                return showSessionSwitcher();
            case "sidebar":
                DrawerLayout drawer = getDrawer();
                if (drawer.getDrawerLockMode(Gravity.START) == DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    return false;
                drawer.openDrawer(Gravity.START);
                return true;
            default:
                throw new IllegalArgumentException("Unknown terminal UI action: " + action);
        }
    }

    public boolean showSessionSwitcher() {
        if (!mIsVisible || mTermuxService == null || isFinishing()
            || getDrawer().getDrawerLockMode(Gravity.START) == DrawerLayout.LOCK_MODE_LOCKED_CLOSED) return false;
        if (sessionSwitcher != null && sessionSwitcher.isShowing()) return true;

        // The picker reuses the sidebar's rows: numbered badges, the current session filled, and its tmux
        // windows in the configured sidebar layout and style.
        LinearLayout list = new LinearLayout(this);
        ScrollView content = pickerContent(list);
        boolean nested = TermuxPropertyConstants.TMUX_SIDEBAR_NESTED.equals(tmuxSidebarLayout());
        View currentRow = null;
        for (int index = 0; index < mTermuxService.getTermuxSessionsSize(); index++) {
            TermuxSession termuxSession = mTermuxService.getTermuxSession(index);
            TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
            if (session == null) continue;
            View row = getLayoutInflater().inflate(R.layout.item_terminal_sessions_list, list, false);
            TermuxSessionsListViewController.bindSessionSummary(this, row, session, index + 1);
            if (session == getCurrentSession()) {
                row.setActivated(true);
                currentRow = row;
            }
            row.findViewById(R.id.session_header).setOnClickListener(view -> {
                dismissSessionSwitcher();
                if (mTermuxService != null && mTermuxService.getTermuxSessionForTerminalSession(session) != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(session);
            });

            LinearLayout windowsView = row.findViewById(R.id.session_tmux_windows);
            JSONArray windows = mTermuxService.getTmuxWindows(session);
            if (windows.length() > 0) {
                windowsView.setVisibility(View.VISIBLE);
                if (nested && !collapsedTmuxSessions.contains(session.mHandle)) {
                    addTmuxWindows(windowsView, session, windows, true, this::dismissSessionSwitcher);
                } else {
                    addTmuxHeading(windowsView, getString(R.string.ui_tmux_sidebar_drill), view -> {
                        dismissSessionSwitcher();
                        showTmuxSwitcher(session);
                    });
                }
            }
            list.addView(row);
        }
        scrollPickerTo(content, currentRow);

        sessionSwitcher = pickerBuilder()
            .setTitle(R.string.ui_session_switcher_title)
            .setView(content)
            .setPositiveButton(R.string.action_new_session, (dialog, which) ->
                mTermuxTerminalSessionActivityClient.addNewSession(false, null))
            .setNegativeButton(R.string.action_open_settings, (dialog, which) ->
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class)))
            .setNeutralButton(R.string.action_toggle_soft_keyboard, (dialog, which) -> {
                dialog.dismiss();
                getWindow().getDecorView().post(() -> mTermuxTerminalViewClient.onToggleSoftKeyboardRequest());
            })
            .create();
        sessionSwitcher.setOnDismissListener(dialog -> sessionSwitcher = null);
        sessionSwitcher.show();
        placePicker(sessionSwitcher);
        return true;
    }

    private void dismissSessionSwitcher() {
        if (sessionSwitcher != null) sessionSwitcher.dismiss();
    }

    private void dismissTmuxSwitcher() {
        if (tmuxSwitcher != null) tmuxSwitcher.dismiss();
    }

    private String pickerPosition() {
        return (String) mProperties.getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_PICKER_POSITION, true);
    }

    /**
     * A dialog builder for the pickers. Material dialogs pad their window with a large transparent inset above
     * and below the card; away from the middle of the screen that would leave a gap, so those pickers keep a
     * small one.
     */
    private MaterialAlertDialogBuilder pickerBuilder() {
        MaterialAlertDialogBuilder builder = DialogUtils.builder(this);
        if (!TermuxPropertyConstants.PICKER_POSITION_CENTER.equals(pickerPosition()))
            builder.setBackgroundInsetTop(sidebarDp(16)).setBackgroundInsetBottom(sidebarDp(16));
        return builder;
    }

    /**
     * Move a picker to where {@code terminal-picker-position} wants it: the top of the screen, the middle of
     * the screen (the dialog's own place), or the middle of the terminal view, which the keyboard shrinks.
     */
    private void placePicker(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        String position = pickerPosition();
        if (TermuxPropertyConstants.PICKER_POSITION_CENTER.equals(position)) return;

        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 0;
        window.setAttributes(params);
        if (!TermuxPropertyConstants.PICKER_POSITION_TERMINAL.equals(position)) return;

        // The dialog's height is only known once it is laid out, and changes with its content: move it then, by
        // the distance between where it is and where it should be on screen, so system bars need no special case.
        View decor = window.getDecorView();
        decor.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!dialog.isShowing() || mTerminalView == null) return;
            int[] terminal = new int[2];
            int[] current = new int[2];
            mTerminalView.getLocationOnScreen(terminal);
            decor.getLocationOnScreen(current);
            int wanted = terminal[1] + Math.max(0, (mTerminalView.getHeight() - decor.getHeight()) / 2);
            int shift = wanted - current[1];
            if (Math.abs(shift) <= 1) return;
            WindowManager.LayoutParams moved = window.getAttributes();
            moved.y = Math.max(0, moved.y + shift);
            window.setAttributes(moved);
        });
    }

    /** A scrolling list for a picker dialog, padded like the sidebar so its rows line up the same way. */
    private ScrollView pickerContent(LinearLayout list) {
        ScrollView scroll = new ScrollView(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPaddingRelative(0, sidebarDp(4), 0, sidebarDp(8));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** Bring the chosen row into view when a long picker opens, with a little of the row before it visible. */
    private void scrollPickerTo(ScrollView scroll, @Nullable View row) {
        if (row == null) return;
        scroll.post(() -> {
            int top = 0;
            for (View view = row; view != null && view != scroll; view = (View) view.getParent())
                top += view.getTop();
            scroll.scrollTo(0, Math.max(0, top - sidebarDp(48)));
        });
    }

    private String tmuxSidebarLayout() {
        return (String) mProperties.getInternalPropertyValue(
            TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT, true);
    }

    private void updateTmuxSidebar() {
        updateRightTmuxSidebar();
        if (mTermuxService == null) return;
        String layout = tmuxSidebarLayout();
        if (!layout.equals(lastTmuxSidebarLayout)) {
            lastTmuxSidebarLayout = layout;
            tmuxSidebarSession = null;
            tmuxSidebarMode = false;
        }

        List<TerminalSession> attachments = new ArrayList<>();
        for (int i = 0; i < mTermuxService.getTermuxSessionsSize(); i++) {
            TerminalSession terminal = mTermuxService.getTermuxSession(i).getTerminalSession();
            if (terminal != null && mTermuxService.getTmuxWindows(terminal).length() > 0)
                attachments.add(terminal);
        }
        if (!attachments.contains(tmuxSidebarSession)) tmuxSidebarSession = null;
        if (attachments.isEmpty()) tmuxSidebarMode = false;

        boolean modes = layout.equals(TermuxPropertyConstants.TMUX_SIDEBAR_MODES) && !attachments.isEmpty();
        boolean showWindows = tmuxSidebarSession != null || tmuxSidebarMode;
        TextView navigation = findViewById(R.id.tmux_sidebar_navigation);
        navigation.setVisibility(modes || tmuxSidebarSession != null ? View.VISIBLE : View.GONE);
        navigation.setText(tmuxSidebarSession != null ? R.string.ui_tmux_sidebar_back
            : tmuxSidebarMode ? R.string.ui_tmux_sidebar_sessions : R.string.ui_tmux_switcher_title);
        navigation.setOnClickListener(view -> {
            if (tmuxSidebarSession != null) tmuxSidebarSession = null;
            else tmuxSidebarMode = !tmuxSidebarMode;
            updateTmuxSidebar();
        });

        findViewById(R.id.terminal_sessions_list).setVisibility(showWindows ? View.GONE : View.VISIBLE);
        View panel = findViewById(R.id.tmux_sidebar_panel);
        panel.setVisibility(showWindows ? View.VISIBLE : View.GONE);
        LinearLayout groups = findViewById(R.id.tmux_sidebar_groups);
        groups.removeAllViews();
        if (!showWindows) return;
        for (TerminalSession terminal : attachments) {
            if (tmuxSidebarSession != null && terminal != tmuxSidebarSession) continue;
            String title = terminal.mSessionName;
            if (TextUtils.isEmpty(title)) title = terminal.getTitle();
            if (TextUtils.isEmpty(title)) title = getString(R.string.ui_session_unnamed,
                mTermuxService.getIndexOfSession(terminal) + 1);
            addTmuxHeading(groups, title, null);
            addTmuxWindows(groups, terminal, mTermuxService.getTmuxWindows(terminal), true, null);
        }
    }

    public void bindTmuxWindows(LinearLayout container, @Nullable TerminalSession session) {
        container.removeAllViews();
        String layout = tmuxSidebarLayout();
        JSONArray windows = session == null || mTermuxService == null
            ? new JSONArray() : mTermuxService.getTmuxWindows(session);
        boolean visible = windows.length() > 0 && !layout.equals(TermuxPropertyConstants.TMUX_SIDEBAR_MODES);
        container.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        if (layout.equals(TermuxPropertyConstants.TMUX_SIDEBAR_DRILL_DOWN)) {
            addTmuxHeading(container, getString(R.string.ui_tmux_sidebar_drill), view -> {
                tmuxSidebarSession = session;
                updateTmuxSidebar();
            });
            return;
        }

        boolean collapsed = collapsedTmuxSessions.contains(session.mHandle);
        container.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (!collapsed) addTmuxWindows(container, session, windows, true, null);
    }

    public int tmuxWindowVisibilityAction(TerminalSession session) {
        if (mTermuxService == null || mTermuxService.getTmuxWindows(session).length() == 0
            || !TermuxPropertyConstants.TMUX_SIDEBAR_NESTED.equals(tmuxSidebarLayout())) return 0;
        return collapsedTmuxSessions.contains(session.mHandle)
            ? R.string.ui_tmux_sidebar_expand : R.string.ui_tmux_sidebar_collapse;
    }

    public void toggleTmuxWindows(TerminalSession session) {
        if (tmuxWindowVisibilityAction(session) == 0) return;
        if (!collapsedTmuxSessions.remove(session.mHandle)) collapsedTmuxSessions.add(session.mHandle);
        termuxSessionListNotifyUpdated();
    }

    private void addTmuxHeading(LinearLayout container, String label, @Nullable View.OnClickListener onClick) {
        TextView heading = createTmuxRow(label);
        heading.setOnClickListener(onClick);
        heading.setClickable(onClick != null);
        container.addView(heading, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Add the window rows of a tmux attachment in the configured sidebar style.
     *
     * @param guide Whether a guide line runs along the rows, to show they belong to the session above.
     * @param onSelected Run after a window is chosen, for example to close the picker the rows are in.
     * @return The row of the active window, if there is one.
     */
    @Nullable
    private View addTmuxWindows(LinearLayout container, TerminalSession session, JSONArray windows,
                                boolean guide, @Nullable Runnable onSelected) {
        boolean rounded = TermuxPropertyConstants.TMUX_SIDEBAR_ROUNDED.equals(mProperties.getInternalPropertyValue(
            TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE, true));
        int foreground = ContextCompat.getColor(this, R.color.ui_on_surface);
        int muted = ContextCompat.getColor(this, R.color.ui_on_surface_variant);
        int accent = MaterialColors.getColor(container, R.attr.termuxUiPrimary);
        int onAccent = MaterialColors.getColor(container, R.attr.termuxUiOnPrimary);
        int shelf = ContextCompat.getColor(this, R.color.ui_session_shelf);

        LinearLayout group = new LinearLayout(this);
        container.addView(group, new LinearLayout.LayoutParams(-1, -2));
        if (guide) {
            View line = new View(this);
            line.setBackgroundColor(ColorUtils.setAlphaComponent(foreground, 30));
            group.addView(line, new LinearLayout.LayoutParams(sidebarDp(1), -1));
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPaddingRelative(sidebarDp(guide ? 12 : 0), sidebarDp(2), 0, sidebarDp(4));
        View activeRow = null;
        group.addView(rows, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            boolean active = window.optBoolean("active");
            String label = window.optString("label");
            String[] parts = label.split("  ", 2);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(sidebarDp(14), 0, sidebarDp(14), 0);
            applyTmuxRipple(row);
            int textColor = active ? rounded ? onAccent : foreground : muted;
            if (active) {
                GradientDrawable fill = new GradientDrawable();
                fill.setCornerRadius(sidebarDp(rounded ? 24 : 12));
                fill.setColor(rounded ? accent : ColorUtils.setAlphaComponent(foreground, 18));
                if (!rounded) fill.setStroke(sidebarDp(1), ColorUtils.setAlphaComponent(foreground, 34));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    MaterialColors.getColor(row, R.attr.termuxUiRipple)), fill, null));
            }
            if (parts.length == 2) {
                TextView number = tmuxWindowText(parts[0], active && !rounded ? accent : textColor, active);
                row.addView(number, new LinearLayout.LayoutParams(sidebarDp(24), -2));
            }
            TmuxProgramIcon program = TmuxProgramIcon.forCommand(window.optString("program", null));
            if (program != null) {
                ImageView icon = new ImageView(this);
                icon.setImageResource(program.drawable);
                // Brand colours would clash with the filled accent pill, so it keeps one colour.
                icon.setImageTintList(ColorStateList.valueOf(active && rounded ? textColor
                    : TmuxProgramIcon.tint(program, textColor, shelf)));
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(sidebarDp(16), sidebarDp(16));
                iconLayout.setMarginEnd(sidebarDp(10));
                row.addView(icon, iconLayout);
            }
            TextView title = tmuxWindowText(parts.length == 2 ? parts[1] : label, textColor, active);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (active) {
                View dot = new View(this);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(rounded ? onAccent : accent);
                dot.setBackground(circle);
                LinearLayout.LayoutParams dotLayout = new LinearLayout.LayoutParams(sidebarDp(7), sidebarDp(7));
                dotLayout.setMarginStart(sidebarDp(8));
                row.addView(dot, dotLayout);
            }
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, sidebarDp(48));
            rowLayout.bottomMargin = sidebarDp(2);
            rows.addView(row, rowLayout);
            row.setContentDescription(active ? getString(R.string.ui_tmux_active_window, label) : label);
            row.setOnClickListener(view -> {
                if (onSelected != null) onSelected.run();
                selectTmuxWindow(session, window.optString("id"));
            });
            if (active) activeRow = row;
        }
        return activeRow;
    }

    private TextView tmuxWindowText(String text, int color, boolean active) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE, active ? Typeface.BOLD : Typeface.NORMAL);
        view.setSingleLine();
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private int sidebarDp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private TextView createTmuxRow(String label) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextAppearance(R.style.TextAppearance_TermuxPP_Body);
        applyTmuxRipple(row);
        int inset = Math.round(12 * getResources().getDisplayMetrics().density);
        row.setPaddingRelative(inset, 0, inset, 0);
        row.setMinHeight(getResources().getDimensionPixelSize(R.dimen.ui_touch_target));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setMaxLines(1);
        return row;
    }

    private void applyTmuxRipple(View view) {
        TypedValue background = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true))
            view.setBackgroundResource(background.resourceId);
    }

    public boolean showTmuxSwitcher(@Nullable TerminalSession session) {
        if (session == null || mTermuxService == null || !mIsVisible || isFinishing()) return false;
        if (tmuxSwitcher != null && tmuxSwitcher.isShowing()) return true;
        JSONArray windows = mTermuxService.getTmuxWindows(session);
        if (windows.length() == 0) return false;

        // Just the windows, as rows like the sidebar's in its subtle or rounded style.
        LinearLayout list = new LinearLayout(this);
        ScrollView content = pickerContent(list);
        list.setPaddingRelative(sidebarDp(12), sidebarDp(12), sidebarDp(12), sidebarDp(8));
        View activeRow = addTmuxWindows(list, session, windows, false, this::dismissTmuxSwitcher);
        scrollPickerTo(content, activeRow);

        tmuxSwitcher = pickerBuilder()
            .setView(content)
            .create();
        tmuxSwitcher.setOnDismissListener(dialog -> tmuxSwitcher = null);
        tmuxSwitcher.show();
        placePicker(tmuxSwitcher);
        return true;
    }

    private void selectTmuxWindow(TerminalSession session, String id) {
        if (mTermuxService == null || !mTermuxService.selectTmuxWindow(session, id)) {
            Toast.makeText(this, R.string.ui_tmux_attachment_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        mTermuxTerminalSessionActivityClient.setCurrentSession(session);
        getDrawer().closeDrawers();
    }

    public void updateRightTmuxSidebar() {
        DrawerLayout drawer = getDrawer();
        boolean enabled = (boolean) mProperties.getInternalPropertyValue(
            TermuxPropertyConstants.KEY_TMUX_RIGHT_SIDEBAR, true);
        boolean copyMode = drawer.getDrawerLockMode(Gravity.START) == DrawerLayout.LOCK_MODE_LOCKED_CLOSED;
        int lockMode = enabled && !copyMode ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED;
        if (drawer.getDrawerLockMode(Gravity.END) != lockMode) drawer.setDrawerLockMode(lockMode, Gravity.END);
        if (!enabled) return;

        TerminalSession session = getCurrentSession();
        JSONArray windows = session == null || mTermuxService == null
            ? new JSONArray() : mTermuxService.getTmuxWindows(session);
        LinearLayout rows = findViewById(R.id.right_tmux_windows);
        rows.removeAllViews();
        findViewById(R.id.right_tmux_empty).setVisibility(windows.length() == 0 ? View.VISIBLE : View.GONE);
        if (windows.length() > 0) addTmuxWindows(rows, session, windows, false, null);
    }

    private void configureSessionShelf() {
        DrawerLayout drawer = getDrawer();
        drawer.setScrimColor(ContextCompat.getColor(this, R.color.ui_session_shelf_scrim));
        drawer.setDrawerTitle(Gravity.END, getString(R.string.ui_tmux_switcher_title));
        updateRightTmuxSidebar();
        View sessions = findViewById(R.id.terminal_sessions_list);
        float revealOffset = 12f * getResources().getDisplayMetrics().density;
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View panel, float offset) {
                if (panel.getId() != R.id.left_drawer) return;
                boolean animated = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || ValueAnimator.areAnimatorsEnabled();
                float direction = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1f : -1f;
                float remaining = 1f - offset;
                sessions.setTranslationX(animated ? direction * revealOffset * remaining * remaining : 0f);
            }

            @Override
            public void onDrawerOpened(@NonNull View panel) {
                if (!(boolean) mProperties.getInternalPropertyValue(
                    TermuxPropertyConstants.KEY_HIDE_SOFT_KEYBOARD_ON_SIDEBAR, true)) return;

                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(drawer);
                if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                    restoreKeyboardAfterSidebar = true;
                    KeyboardUtils.hideSoftKeyboard(TermuxActivity.this, drawer);
                }
            }

            @Override
            public void onDrawerClosed(@NonNull View panel) {
                if (!restoreKeyboardAfterSidebar || drawer.isDrawerVisible(Gravity.START)
                    || drawer.isDrawerVisible(Gravity.END)) return;
                restoreKeyboardAfterSidebar = false;
                if (!mIsVisible || !hasWindowFocus() || isFinishing()
                    || KeyboardUtils.areDisableSoftKeyboardFlagsSet(TermuxActivity.this)) return;

                View input = getCurrentFocus();
                if (input == null || !input.onCheckIsTextEditor()) {
                    input = mTerminalView;
                    if (!input.requestFocus()) return;
                }
                KeyboardUtils.showSoftKeyboard(TermuxActivity.this, input);
            }
        });
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    /**
     * Switches the visible terminal toolbar to its text input, or back to the extra keys if the
     * text input is selected. Does nothing while the toolbar is hidden.
     */
    public void toggleTerminalToolbarTextInput() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null || terminalToolbarViewPager.getVisibility() != View.VISIBLE) return;
        terminalToolbarViewPager.setCurrentItem(isTerminalToolbarTextInputViewSelected() ? 0 : 1, true);
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        if (mTermuxSessionListViewController != null) mTermuxSessionListViewController.notifyDataSetChanged();
        updateTmuxSidebar();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void onPropertiesEditedFromSettings(Context context) {
        // Settings can stop the terminal activity, which temporarily unregisters its receiver.
        pendingPropertiesReload = true;
        Intent intent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.setButtonActiveColorOverrides(
                    mProperties.getExtraKeysButtonActiveTextColor(),
                    mProperties.getExtraKeysButtonActiveBackgroundColor());
                mExtraKeysView.setButtonCornerRadiusDp(mProperties.getExtraKeysButtonCornerRadius());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);
        termuxSessionListNotifyUpdated();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
