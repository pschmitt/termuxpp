package com.termux.app.terminal;

import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import dev.pschmitt.termux.shared.file.FileUtils;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.interact.MessageDialogUtils;
import dev.pschmitt.termux.shared.interact.ShareUtils;
import dev.pschmitt.termux.shared.shell.ShellUtils;
import dev.pschmitt.termux.shared.termux.TermuxBootstrap;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;
import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxSharedProperties;
import dev.pschmitt.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import dev.pschmitt.termux.shared.termux.extrakeys.SpecialButton;
import dev.pschmitt.termux.shared.termux.extrakeys.TerminalKeySequence;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import dev.pschmitt.termux.shared.android.AndroidUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.activities.ReportActivity;
import dev.pschmitt.termux.shared.models.ReportInfo;
import com.termux.app.models.UserAction;
import com.termux.app.terminal.io.KeyboardShortcut;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import dev.pschmitt.termux.shared.data.DataUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.markdown.MarkdownUtils;
import dev.pschmitt.termux.shared.termux.TermuxUtils;
import dev.pschmitt.termux.shared.termux.data.TermuxUrlUtils;
import dev.pschmitt.termux.shared.view.KeyboardUtils;
import dev.pschmitt.termux.shared.view.ViewUtils;
import com.termux.view.MultiFingerSwipe;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalHyperlink;
import com.termux.terminal.TerminalSession;
import dev.pschmitt.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;

public class TermuxTerminalViewClient extends TermuxTerminalViewClientBase {

    final TermuxActivity mActivity;

    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /** Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys. */
    boolean mVirtualControlKeyDown, mVirtualFnKeyDown;

    private Runnable mShowSoftKeyboardRunnable;

    private boolean mShowSoftKeyboardIgnoreOnce;
    private boolean mShowSoftKeyboardWithDelayOnce;

    private boolean mTerminalCursorBlinkerStateAlreadySet;

    private List<KeyboardShortcut> mSessionShortcuts;
    private final GestureFeedback mGestureFeedback = new GestureFeedback();

    private static final String LOG_TAG = "TermuxTerminalViewClient";

    public TermuxTerminalViewClient(TermuxActivity activity, TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        this.mActivity = activity;
        this.mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;
    }

    public TermuxActivity getActivity() {
        return mActivity;
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        onReloadProperties();

        mActivity.getTerminalView().setTextSize(mActivity.getPreferences().getFontSize());
        mActivity.getTerminalView().setKeepScreenOn(mActivity.getPreferences().shouldKeepScreenOn());
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // Set {@link TerminalView#TERMINAL_VIEW_KEY_LOGGING_ENABLED} value
        // Also required if user changed the preference from {@link TermuxSettings} activity and returns
        boolean isTerminalViewKeyLoggingEnabled = mActivity.getPreferences().isTerminalViewKeyLoggingEnabled();
        mActivity.getTerminalView().setIsTerminalViewKeyLoggingEnabled(isTerminalViewKeyLoggingEnabled);

        // Piggyback on the terminal view key logging toggle for now, should add a separate toggle in future
        mActivity.getTermuxActivityRootView().setIsRootViewLoggingEnabled(isTerminalViewKeyLoggingEnabled);
        ViewUtils.setIsViewUtilsLoggingEnabled(isTerminalViewKeyLoggingEnabled);
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Show the soft keyboard if required
        setSoftKeyboardState(true, mActivity.isActivityRecreated());

        mTerminalCursorBlinkerStateAlreadySet = false;

        if (mActivity.getTerminalView().mEmulator != null) {
            // Start terminal cursor blinking if enabled
            // If emulator is already set, then start blinker now, otherwise wait for onEmulatorSet()
            // event to start it. This is needed since onEmulatorSet() may not be called after
            // TermuxActivity is started after device display timeout with double tap and not power button.
            setTerminalCursorBlinkerState(true);
            mTerminalCursorBlinkerStateAlreadySet = true;
        }
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        // Stop terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(false);
    }

    /**
     * Should be called when mActivity.reloadProperties() is called
     */
    public void onReloadProperties() {
        setSessionShortcuts();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Show the soft keyboard if required
        setSoftKeyboardState(false, true);

        // Start terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(true);
    }

    /**
     * Should be called when {@link com.termux.view.TerminalView#mEmulator} is set
     */
    @Override
    public void onEmulatorSet() {
        if (!mTerminalCursorBlinkerStateAlreadySet) {
            // Start terminal cursor blinking if enabled
            // We need to wait for the first session to be attached that's set in
            // TermuxActivity.onServiceConnected() and then the multiple calls to TerminalView.updateSize()
            // where the final one eventually sets the mEmulator when width/height is not 0. Otherwise
            // blinker will not start again if TermuxActivity is started again after exiting it with
            // double back press. Check TerminalView.setTerminalCursorBlinkerState().
            setTerminalCursorBlinkerState(true);
            mTerminalCursorBlinkerStateAlreadySet = true;
        }
    }



    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            boolean increase = scale > 1.f;
            changeFontSize(increase);
            return 1.0f;
        }
        return scale;
    }



    @Override
    public void onSingleTapUp(MotionEvent e) {
        TerminalEmulator term = mActivity.getCurrentSession().getEmulator();

        if (mActivity.getProperties().shouldOpenTerminalTranscriptURLOnClick()) {
            int[] columnAndRow = mActivity.getTerminalView().getColumnAndRow(e, true);
            if (openUrlAt(term, columnAndRow[0], columnAndRow[1])) return;
        }

        // A touch tap acts (soft keyboard, session switch, sidebar) even while a terminal
        // application (like tmux with `mouse on`) has enabled mouse tracking. TerminalView already
        // forwarded the tap to the application as a mouse click in onUp(), and this callback does
        // not report anything.
        TerminalGestureAction singleTapAction = mActivity.getProperties().getTerminalSingleTapAction();
        if (shouldActOnSingleTapUp(e, getMaxTapActionCount(), singleTapAction))
            performTerminalTapAction(1, singleTapAction, e.getX(), e.getY());
    }

    /** How many rows above and below a tapped row are searched for the rest of a URL that was broken over rows. */
    private static final int URL_TAP_WINDOW_ROWS = TermuxUrlUtils.MAX_CONTINUATION_ROWS + 2;

    /**
     * Open the link at a cell of the terminal: the `OSC 8` hyperlink of the cell, else the URL in the
     * text there, including a URL that the application has broken over several rows.
     * <p>
     * Nothing is opened for a cell without a link. A hyperlink whose text does not show its address
     * has to be confirmed first, see {@link #openHyperlink(TerminalHyperlink, String)}.
     *
     * @param row The external row, which is negative for the transcript.
     * @return Returns `true` if there was a link at the cell.
     */
    private boolean openUrlAt(@NonNull TerminalEmulator term, int column, int row) {
        TerminalBuffer screen = term.getScreen();

        TerminalHyperlink hyperlink = screen.getHyperlinkAt(row, column);
        if (hyperlink != null) {
            openHyperlink(hyperlink, screen.getHyperlinkLabelAt(row, column));
            return true;
        }

        String url = findTextUrlAt(screen, term.mColumns, column, row);
        if (url == null) return false;
        ShareUtils.openUrl(mActivity, url);
        return true;
    }

    /** Get the URL in the text of the terminal at a cell, see {@link TermuxUrlUtils#findUrlAtOffset(String, int, int)}. */
    @Nullable
    static String findTextUrlAt(@NonNull TerminalBuffer screen, int columns, int column, int row) {
        // A blank cell is never part of a URL.
        String cell = screen.getSelectedText(column, row, column, row, false);
        if (cell == null || cell.trim().isEmpty()) return null;

        int firstRow = Math.max(-screen.getActiveTranscriptRows(), row - URL_TAP_WINDOW_ROWS);
        int lastRow = row + URL_TAP_WINDOW_ROWS; // Clamped to the screen by getSelectedText().

        // The text of the rows around the tap, with the rows that the terminal wrapped itself joined.
        // The text from the first row up to the cell is the start of the same text, so its length
        // is the offset of the cell.
        String text = screen.getSelectedText(0, firstRow, columns, lastRow, true, false);
        String textUpToCell = screen.getSelectedText(0, firstRow, column, row, true, false);
        if (text == null || textUpToCell == null || textUpToCell.isEmpty()) return null;
        return TermuxUrlUtils.findUrlAtOffset(text, columns, textUpToCell.length() - 1);
    }

    /**
     * Open an `OSC 8` hyperlink that was tapped. If the text that is shown for it is not its address
     * (which is the point of such links, but also how a link can disguise where it leads), the
     * address is shown and has to be confirmed.
     */
    private void openHyperlink(@NonNull TerminalHyperlink hyperlink, @Nullable String label) {
        final String uri = hyperlink.getUri();
        if (!hyperlink.isLabelConcealingTarget(label)) {
            ShareUtils.openUrl(mActivity, uri);
            return;
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
            DialogUtils.builder(mActivity, R.drawable.ui_ic_link, false);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_hyperlink, null);
        TextView labelView = content.findViewById(R.id.hyperlink_label);
        labelView.setText(getLabelForDisplay(label));
        AlertDialog dialog = builder.setTitle(R.string.title_open_hyperlink)
            .setView(content)
            .setPositiveButton(R.string.action_open_hyperlink, (di, which) -> ShareUtils.openUrl(mActivity, uri))
            .setNeutralButton(R.string.action_copy_hyperlink, (di, which) ->
                ShareUtils.copyTextToClipboard(mActivity, uri, mActivity.getString(R.string.msg_hyperlink_copied_to_clipboard)))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        TextView addressView = content.findViewById(R.id.hyperlink_address);
        SpannableString address = new SpannableString(uri);
        address.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                dialog.dismiss();
                ShareUtils.openUrl(mActivity, uri);
            }
        }, 0, address.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        addressView.setText(address);
        addressView.setMovementMethod(LinkMovementMethod.getInstance());
        dialog.show();
    }

    /** The text of a link on one line and of a bounded length. */
    @NonNull
    private static String getLabelForDisplay(@Nullable String label) {
        if (label == null) return "";
        String oneLine = label.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "\u2026" : oneLine;
    }

    /**
     * Whether a confirmed single tap acts. Taps from an external mouse never do, and neither do
     * taps with the action none, or while a double or triple tap action is configured, in which
     * case {@link #onTapCount(int)} acts on single taps once it is clear that no more taps follow.
     * Whether the terminal application has enabled mouse tracking is intentionally not considered,
     * since the click is forwarded independently.
     */
    static boolean shouldActOnSingleTapUp(MotionEvent e, int maxTapActionCount, TerminalGestureAction singleTapAction) {
        return !e.isFromSource(InputDevice.SOURCE_MOUSE)
            && maxTapActionCount <= 1
            && singleTapAction != TerminalGestureAction.NONE;
    }

    @Override
    public int getMaxTapActionCount() {
        return mActivity.getProperties().getTerminalMaxTapActionCount();
    }

    /**
     * Acts on the configured action of a tap sequence of {@code tapCount} taps. Only called while a
     * double or triple tap action is configured, see {@link #getMaxTapActionCount()}.
     */
    @Override
    public void onTapCount(int tapCount, float x, float y) {
        if (tapCount == 3 && focusTripleTapSession()) {
            showGestureFeedback(mActivity.getString(R.string.ui_gesture_focus_session), x, y);
            return;
        }
        performTerminalTapAction(tapCount, mActivity.getProperties().getTerminalTapAction(tapCount), x, y);
    }

    @Override
    public boolean hasMultiFingerSwipeAction(int fingers, MultiFingerSwipe.Direction direction) {
        return mActivity.getProperties().getTerminalMultiFingerSwipeAction(
            fingers, direction.name().toLowerCase(Locale.ROOT)) != TerminalGestureAction.NONE;
    }

    @Override
    public void onMultiFingerSwipe(int fingers, MultiFingerSwipe.Direction direction, float x, float y) {
        String name = direction.name().toLowerCase(Locale.ROOT);
        TermuxAppSharedProperties properties = mActivity.getProperties();
        TerminalGestureAction action = properties.getTerminalMultiFingerSwipeAction(fingers, name);
        if (action == TerminalGestureAction.NONE) return;
        showGestureFeedback(action.getLabel(), x, y);
        performGestureAction(action, properties.getTerminalMultiFingerSwipeKeys(fingers, name),
            properties.getTerminalMultiFingerSwipeCommand(fingers, name), properties.getGestureCommandContext());
    }

    public void showGestureFeedback(String label) {
        showGestureFeedback(label, Float.NaN, Float.NaN);
    }

    private void showGestureFeedback(String label, float x, float y) {
        if (mActivity.getProperties().areGestureAnimationsEnabled()) {
            String style = (String) mActivity.getProperties().getInternalPropertyValue(
                TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE, true);
            mGestureFeedback.confirm(mActivity.getTerminalView(), label, style, x, y,
                mActivity.getProperties().getGestureFeedbackGravity());
        }
    }

    public void previewGesture(@Nullable String label, boolean armed) {
        mGestureFeedback.preview(mActivity.getTerminalView(), label, armed,
            mActivity.getProperties().getGestureFeedbackGravity());
    }

    private void performTerminalTapAction(int tapCount, TerminalGestureAction action, float x, float y) {
        if (action != TerminalGestureAction.NONE
            && (tapCount != 1 || action != TermuxPropertyConstants.DEFAULT_TERMINAL_SINGLE_TAP_ACTION))
            showGestureFeedback(action.getLabel(), x, y);
        performGestureAction(action,
            mActivity.getProperties().getTerminalTapKeys(tapCount),
            mActivity.getProperties().getTerminalTapCommand(tapCount),
            mActivity.getProperties().getGestureCommandContext());
    }

    private boolean focusTripleTapSession() {
        String expression = mActivity.getProperties().getPropertyValue(
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE, "", true);
        TermuxService service = mActivity.getTermuxService();
        if (expression.isEmpty() || service == null) return false;

        Pattern titlePattern;
        try {
            titlePattern = Pattern.compile(expression);
        } catch (PatternSyntaxException e) {
            return false;
        }
        TerminalSession current = mActivity.getCurrentSession();
        if (matchesSessionTitle(current, titlePattern)) return true;
        for (TermuxSession session : service.getTermuxSessions()) {
            TerminalSession terminal = session.getTerminalSession();
            if (matchesSessionTitle(terminal, titlePattern)) {
                mTermuxTerminalSessionActivityClient.setCurrentSession(terminal);
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSessionTitle(@Nullable TerminalSession session, Pattern pattern) {
        if (session == null || !session.isRunning()) return false;
        String title = session.getTitle();
        return title != null && pattern.matcher(title).find();
    }

    /**
     * Carries out an action configured for a tap or a swipe. Hiding the keyboard is only a hide:
     * it neither disables the keyboard nor changes the keyboard toggle preference.
     */
    public void performGestureAction(@NonNull TerminalGestureAction action) {
        performGestureAction(action, null);
    }

    /**
     * Carries out an action configured for a tap or a swipe.
     *
     * @param keys The keys to send for {@link TerminalGestureAction#SEND_KEYS}.
     */
    public void performGestureAction(@NonNull TerminalGestureAction action, @Nullable TerminalKeySequence keys) {
        performGestureAction(action, keys, null, GestureCommandContext.SESSION);
    }

    /** Carries out an action configured for a tap or swipe, including its command context. */
    public void performGestureAction(@NonNull TerminalGestureAction action, @Nullable TerminalKeySequence keys,
                                     @Nullable String command, @NonNull GestureCommandContext commandContext) {
        GestureActionDispatcher.perform(action, keys, command, commandContext, new GestureActionDispatcher.Target() {
            @Override
            public int getSessionCount() {
                TermuxService service = mActivity.getTermuxService();
                return service == null ? 0 : service.getTermuxSessionsSize();
            }

            @Override
            public void switchSession(boolean forward) {
                mTermuxTerminalSessionActivityClient.switchToSession(forward);
            }

            @Override
            public void createSession() {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
            }

            @Override
            public void toggleTextInput() {
                mActivity.toggleTerminalToolbarTextInput();
            }

            @SuppressLint("RtlHardcoded")
            @Override
            public void openSidebar() {
                DrawerLayout drawer = mActivity.getDrawer();
                // The drawer is locked closed while copying.
                if (drawer.getDrawerLockMode(Gravity.LEFT) != DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    drawer.openDrawer(Gravity.LEFT);
            }

            @Override
            public void openSessionSwitcher() {
                mActivity.showSessionSwitcher();
            }

            @Override
            public void openMenu() {
                // The one funnel that the long press uses too, so that the same sheet (or the system
                // menu as its fallback) is shown and a selection is never duplicated.
                mActivity.getTerminalView().showContextMenu();
            }

            @Override
            public void changeFontSize(boolean increase) {
                TermuxTerminalViewClient.this.changeFontSize(increase);
            }

            @Override
            public boolean isSoftKeyboardDisabled() {
                boolean disabled = KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity);
                if (disabled) Logger.logVerbose(LOG_TAG, "Not touching soft keyboard on gesture since its disabled");
                return disabled;
            }

            @Override
            public boolean isSoftKeyboardVisible() {
                // Visibility can only be read from the window insets since Android 6. Before that
                // the keyboard is only ever shown.
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && KeyboardUtils.isSoftKeyboardVisible(mActivity);
            }

            @Override
            public void showSoftKeyboard() {
                Logger.logVerbose(LOG_TAG, "Showing soft keyboard on gesture");
                mActivity.getTerminalView().requestFocus();
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
            }

            @Override
            public void hideSoftKeyboard() {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on gesture");
                KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
            }

            @Override
            public void sendKeys(@NonNull TerminalKeySequence keys) {
                TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
                if (extraKeys == null) {
                    Logger.logError(LOG_TAG, "Not sending the keys of the gesture since the extra keys are not set up");
                    return;
                }
                try {
                    extraKeys.sendKeys(keys);
                } catch (RuntimeException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to send the keys \"" + keys.getNormalizedValue() + "\" of the gesture", e);
                }
            }

            @Override
            public void runCommand(@NonNull String command, @NonNull GestureCommandContext context) {
                TermuxTerminalViewClient.this.runGestureCommand(command, context);
            }
        });
    }

    /** Start a configured command in a new session, leaving the currently running process alone. */
    public void runGestureCommand(@NonNull String command, @NonNull GestureCommandContext context) {
        TermuxService service = mActivity.getTermuxService();
        if (service != null) service.executeGestureCommand(command, context);
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return mActivity.getProperties().isBackKeyTheEscapeKey();
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return mActivity.getProperties().isEnforcingCharBasedInput();
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return mActivity.getProperties().isUsingCtrlSpaceWorkaround();
    }

    @Override
    public boolean isTerminalViewSelected() {
        return mActivity.getTerminalToolbarViewPager() == null || mActivity.isTerminalViewSelected() || mActivity.getTerminalView().hasFocus();
    }



    @Override
    public void copyModeChanged(boolean copyMode) {
        // Disable drawer while copying.
        mActivity.getDrawer().setDrawerLockMode(copyMode ? DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED);
    }



    @SuppressLint("RtlHardcoded")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) {
        if (handleVirtualKeys(keyCode, e, true)) return true;

        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession);
            return true;
        } else if (!mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && e.isAltPressed()) {
            // Get the unmodified code point:
            int unicodeChar = e.getUnicodeChar(0);

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'/* next */) {
                mTermuxTerminalSessionActivityClient.switchToSession(true);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p' /* previous */) {
                mTermuxTerminalSessionActivityClient.switchToSession(false);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mActivity.getDrawer().openDrawer(Gravity.LEFT);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                mActivity.getDrawer().closeDrawers();
            } else if (unicodeChar == 'k'/* keyboard */) {
                onToggleSoftKeyboardRequest();
            } else if (unicodeChar == 'm'/* menu */) {
                mActivity.getTerminalView().showContextMenu();
            } else if (unicodeChar == 'r'/* rename */) {
                mTermuxTerminalSessionActivityClient.renameSession(currentSession);
            } else if (unicodeChar == 'c'/* create */) {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
            } else if (unicodeChar == 'u' /* urls */) {
                showUrlSelection();
            } else if (unicodeChar == 'v') {
                doPaste();
            } else if (unicodeChar == '+' || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+') {
                // We also check for the shifted char here since shift may be required to produce '+',
                // see https://github.com/termux/termux-api/issues/2
                changeFontSize(true);
            } else if (unicodeChar == '-') {
                changeFontSize(false);
            } else if (unicodeChar >= '1' && unicodeChar <= '9') {
                int index = unicodeChar - '1';
                mTermuxTerminalSessionActivityClient.switchToSession(index);
            }
            return true;
        }

        return false;

    }



    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        // If emulator is not set, like if bootstrap installation failed and user dismissed the error
        // dialog, then just exit the activity, otherwise they will be stuck in a broken state.
        if (keyCode == KeyEvent.KEYCODE_BACK && mActivity.getTerminalView().mEmulator == null) {
            mActivity.finishActivityIfNotFinishing();
            return true;
        }

        return handleVirtualKeys(keyCode, e, false);
    }

    /** What to do with a volume button event, see {@link #resolveVolumeKey}. */
    enum VolumeKeyOutcome {
        /** Not handled here: the system adjusts the volume. */
        PASS_THROUGH,
        /** The button is the virtual Ctrl (volume down) or Fn (volume up) key while it is held. */
        MODIFIER,
        /** Swallowed without effect, so that the volume does not change either. */
        CONSUME,
        /** Swallowed, and the configured action is performed. */
        PERFORM
    }

    /**
     * Decides what a volume button event does.
     *
     * @param action What the button is configured to do: {@code null} for the virtual modifier
     *               (the default), see {@link TermuxSharedProperties#getVolumeUpAction()}.
     * @param down Whether this is a key down (otherwise a key up) event.
     * @param repeatCount {@link KeyEvent#getRepeatCount()}, above 0 while a held button auto repeats.
     */
    static VolumeKeyOutcome resolveVolumeKey(@Nullable TerminalGestureAction action, boolean down, int repeatCount) {
        if (action == null || action == TerminalGestureAction.RUN_COMMAND) return VolumeKeyOutcome.MODIFIER;
        if (action == TerminalGestureAction.NONE) return VolumeKeyOutcome.PASS_THROUGH;
        // An action runs on the press and never on the release, and only the font size actions
        // run again for every auto repeat of a held button.
        return down && action.runsOnKeyDown(repeatCount) ? VolumeKeyOutcome.PERFORM : VolumeKeyOutcome.CONSUME;
    }

    /** Handle dedicated volume buttons as virtual keys or configured actions if applicable. */
    private boolean handleVirtualKeys(int keyCode, KeyEvent event, boolean down) {
        InputDevice inputDevice = event.getDevice();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false;
        } else if (mActivity.getProperties().areVirtualVolumeKeysDisabled()) {
            return false;
        } else if (inputDevice != null && inputDevice.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard.
            return false;
        }

        boolean volumeUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        TermuxSharedProperties properties = mActivity.getProperties();
        TerminalGestureAction action = volumeUp ? properties.getVolumeUpAction() : properties.getVolumeDownAction();
        VolumeKeyOutcome outcome = resolveVolumeKey(action, down, event.getRepeatCount());

        // A modifier that was pressed before the configuration was reloaded must not stay stuck.
        if (outcome != VolumeKeyOutcome.MODIFIER) {
            if (volumeUp) mVirtualFnKeyDown = false; else mVirtualControlKeyDown = false;
        }

        switch (outcome) {
            case MODIFIER:
                if (volumeUp) mVirtualFnKeyDown = down; else mVirtualControlKeyDown = down;
                return true;
            case PERFORM:
                showGestureFeedback(action.getLabel());
                performGestureAction(action, volumeUp ? properties.getVolumeUpKeys() : properties.getVolumeDownKeys());
                return true;
            case CONSUME:
                return true;
            case PASS_THROUGH:
            default:
                return false;
        }
    }



    @Override
    public boolean readControlKey() {
        return readExtraKeysSpecialButton(SpecialButton.CTRL) || mVirtualControlKeyDown;
    }

    @Override
    public boolean readAltKey() {
        return readExtraKeysSpecialButton(SpecialButton.ALT);
    }

    @Override
    public boolean readShiftKey() {
        return readExtraKeysSpecialButton(SpecialButton.SHIFT);
    }

    @Override
    public boolean readFnKey() {
        return readExtraKeysSpecialButton(SpecialButton.FN);
    }

    public boolean readExtraKeysSpecialButton(SpecialButton specialButton) {
        if (mActivity.getExtraKeysView() == null) return false;
        Boolean state = mActivity.getExtraKeysView().readSpecialButton(specialButton, true);
        if (state == null) {
            Logger.logError(LOG_TAG,"Failed to read an unregistered " + specialButton + " special button value from extra keys.");
            return false;
        }
        return state;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }



    @Override
    public boolean onCodePoint(final int codePoint, boolean ctrlDown, TerminalSession session) {
        if (mVirtualFnKeyDown) {
            int resultingKeyCode = -1;
            int resultingCodePoint = -1;
            boolean altDown = false;
            int lowerCase = Character.toLowerCase(codePoint);
            switch (lowerCase) {
                // Arrow keys.
                case 'w':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP;
                    break;
                case 'a':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                    break;
                case 's':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                    break;
                case 'd':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                    break;

                // Page up and down.
                case 'p':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP;
                    break;
                case 'n':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN;
                    break;

                // Some special keys:
                case 't':
                    resultingKeyCode = KeyEvent.KEYCODE_TAB;
                    break;
                case 'i':
                    resultingKeyCode = KeyEvent.KEYCODE_INSERT;
                    break;
                case 'h':
                    resultingCodePoint = '~';
                    break;

                // Special characters to input.
                case 'u':
                    resultingCodePoint = '_';
                    break;
                case 'l':
                    resultingCodePoint = '|';
                    break;

                // Function keys.
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    resultingKeyCode = (codePoint - '1') + KeyEvent.KEYCODE_F1;
                    break;
                case '0':
                    resultingKeyCode = KeyEvent.KEYCODE_F10;
                    break;

                // Other special keys.
                case 'e':
                    resultingCodePoint = /*Escape*/ 27;
                    break;
                case '.':
                    resultingCodePoint = /*^.*/ 28;
                    break;

                case 'b': // alt+b, jumping backward in readline.
                case 'f': // alf+f, jumping forward in readline.
                case 'x': // alt+x, common in emacs.
                    resultingCodePoint = lowerCase;
                    altDown = true;
                    break;

                // Volume control.
                case 'v':
                    resultingCodePoint = -1;
                    AudioManager audio = (AudioManager) mActivity.getSystemService(Context.AUDIO_SERVICE);
                    audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                    break;

                // Writing mode:
                case 'q':
                case 'k':
                    mActivity.toggleTerminalToolbar();
                    mVirtualFnKeyDown=false; // force disable fn key down to restore keyboard input into terminal view, fixes termux/termux-app#1420
                    break;
            }

            if (resultingKeyCode != -1) {
                TerminalEmulator term = session.getEmulator();
                session.write(KeyHandler.getCode(resultingKeyCode, 0, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode()));
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint);
            }
            return true;
        } else if (ctrlDown) {
            if (codePoint == 106 /* Ctrl+j or \n */ && !session.isRunning()) {
                mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                return true;
            }

            List<KeyboardShortcut> shortcuts = mSessionShortcuts;
            if (shortcuts != null && !shortcuts.isEmpty()) {
                int codePointLowerCase = Character.toLowerCase(codePoint);
                for (int i = shortcuts.size() - 1; i >= 0; i--) {
                    KeyboardShortcut shortcut = shortcuts.get(i);
                    if (codePointLowerCase == shortcut.codePoint) {
                        switch (shortcut.shortcutAction) {
                            case TermuxPropertyConstants.ACTION_SHORTCUT_CREATE_SESSION:
                                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_NEXT_SESSION:
                                mTermuxTerminalSessionActivityClient.switchToSession(true);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_PREVIOUS_SESSION:
                                mTermuxTerminalSessionActivityClient.switchToSession(false);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_RENAME_SESSION:
                                mTermuxTerminalSessionActivityClient.renameSession(mActivity.getCurrentSession());
                                return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Set the terminal sessions shortcuts.
     */
    private void setSessionShortcuts() {
        mSessionShortcuts = new ArrayList<>();

        // The {@link TermuxPropertyConstants#MAP_SESSION_SHORTCUTS} stores the session shortcut key and action pair
        for (Map.Entry<String, Integer> entry : TermuxPropertyConstants.MAP_SESSION_SHORTCUTS.entrySet()) {
            // The mMap stores the code points for the session shortcuts while loading properties
            Integer codePoint = (Integer) mActivity.getProperties().getInternalPropertyValue(entry.getKey(), true);
            // If codePoint is null, then session shortcut did not exist in properties or was invalid
            // as parsed by {@link #getCodePointForSessionShortcuts(String,String)}
            // If codePoint is not null, then get the action for the MAP_SESSION_SHORTCUTS key and
            // add the code point to sessionShortcuts
            if (codePoint != null)
                mSessionShortcuts.add(new KeyboardShortcut(codePoint, entry.getValue()));
        }
    }





    public void changeFontSize(boolean increase) {
        mActivity.getPreferences().changeFontSize(increase);
        mActivity.getTerminalView().setTextSize(mActivity.getPreferences().getFontSize());
    }



    /**
     * Called when user requests the soft keyboard to be toggled via "KEYBOARD" toggle button in
     * drawer or extra keys, or with ctrl+alt+k hardware keyboard shortcut.
     */
    public void onToggleSoftKeyboardRequest() {
        // If soft keyboard toggle behaviour is enable/disabled
        if (mActivity.getProperties().shouldEnableDisableSoftKeyboardOnToggle()) {
            // If soft keyboard is visible
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                Logger.logVerbose(LOG_TAG, "Disabling soft keyboard on toggle");
                mActivity.getPreferences().setSoftKeyboardEnabled(false);
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView());
            } else {
                // Show with a delay, otherwise pressing keyboard toggle won't show the keyboard after
                // switching back from another app if keyboard was previously disabled by user.
                // Also request focus, since it wouldn't have been requested at startup by
                // setSoftKeyboardState if keyboard was disabled. #2112
                Logger.logVerbose(LOG_TAG, "Enabling soft keyboard on toggle");
                mActivity.getPreferences().setSoftKeyboardEnabled(true);
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                if(mShowSoftKeyboardWithDelayOnce) {
                    mShowSoftKeyboardWithDelayOnce = false;
                    mActivity.getTerminalView().postDelayed(getShowSoftKeyboardRunnable(), 500);
                    mActivity.getTerminalView().requestFocus();
                } else
                    KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
            }
        }
        // If soft keyboard toggle behaviour is show/hide
        else {
            // If soft keyboard is disabled by user for Termux
            if (!mActivity.getPreferences().isSoftKeyboardEnabled()) {
                Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard on toggle");
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView());
            } else {
                Logger.logVerbose(LOG_TAG, "Showing/Hiding soft keyboard on toggle");
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                KeyboardUtils.toggleSoftKeyboard(mActivity);
            }
        }
    }

    public void setSoftKeyboardState(boolean isStartup, boolean isReloadTermuxProperties) {
        boolean noShowKeyboard = false;

        // Requesting terminal view focus is necessary regardless of if soft keyboard is to be
        // disabled or hidden at startup, otherwise if hardware keyboard is attached and user
        // starts typing on hardware keyboard without tapping on the terminal first, then a colour
        // tint will be added to the terminal as highlight for the focussed view. Test with a light
        // theme. For android 8.+, the "defaultFocusHighlightEnabled" attribute is also set to false
        // in TerminalView layout to fix the issue.

        // If soft keyboard is disabled by user for Termux (check function docs for Termux behaviour info)
        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
            mActivity.getPreferences().isSoftKeyboardEnabled(),
            mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware())) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard");
            KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView());
            mActivity.getTerminalView().requestFocus();
            noShowKeyboard = true;
            // Delay is only required if onCreate() is called like when Termux app is exited with
            // double back press, not when Termux app is switched back from another app and keyboard
            // toggle is pressed to enable keyboard
            if (isStartup && mActivity.isOnResumeAfterOnCreate())
                mShowSoftKeyboardWithDelayOnce = true;
        } else {
            // Set flag to automatically push up TerminalView when keyboard is opened instead of showing over it
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity);

            // Clear any previous flags to disable soft keyboard in case setting updated
            KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);

            // If soft keyboard is to be hidden on startup
            if (isStartup && mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup");
                // Required to keep keyboard hidden when Termux app is switched back from another app
                KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);

                KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
                mActivity.getTerminalView().requestFocus();
                noShowKeyboard = true;
                // Required to keep keyboard hidden on app startup
                mShowSoftKeyboardIgnoreOnce = true;
            }
        }

        mActivity.getTerminalView().setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                // Force show soft keyboard if TerminalView or toolbar text input view has
                // focus and close it if they don't
                boolean textInputViewHasFocus = false;
                final EditText textInputView =  mActivity.findViewById(R.id.terminal_toolbar_text_input);
                if (textInputView != null) textInputViewHasFocus = textInputView.hasFocus();

                if (hasFocus || textInputViewHasFocus) {
                    if (mShowSoftKeyboardIgnoreOnce) {
                        mShowSoftKeyboardIgnoreOnce = false; return;
                    }
                    Logger.logVerbose(LOG_TAG, "Showing soft keyboard on focus change");
                } else {
                    Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on focus change");
                }

                KeyboardUtils.setSoftKeyboardVisibility(getShowSoftKeyboardRunnable(), mActivity, mActivity.getTerminalView(), hasFocus || textInputViewHasFocus);
            }
        });

        // Do not force show soft keyboard if termux-reload-settings command was run with hardware keyboard
        // or soft keyboard is to be hidden or is disabled
        if (!isReloadTermuxProperties && !noShowKeyboard
            && mActivity.findViewById(R.id.bootstrap_overlay) == null) {
            // Request focus for TerminalView
            // Also show the keyboard, since onFocusChange will not be called if TerminalView already
            // had focus on startup to show the keyboard, like when opening url with context menu
            // "Extract URL" long press and returning to Termux app with back button. This
            // will also show keyboard even if it was closed before opening url. #2111
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard");
            mActivity.getTerminalView().requestFocus();
            mActivity.getTerminalView().postDelayed(getShowSoftKeyboardRunnable(), 300);
        }
    }

    private Runnable getShowSoftKeyboardRunnable() {
        if (mShowSoftKeyboardRunnable == null) {
            mShowSoftKeyboardRunnable = () -> {
                if (mActivity.findViewById(R.id.bootstrap_overlay) != null) return;
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
            };
        }
        return mShowSoftKeyboardRunnable;
    }



    public void setTerminalCursorBlinkerState(boolean start) {
        if (start) {
            // If set/update the cursor blinking rate is successful, then enable cursor blinker
            if (mActivity.getTerminalView().setTerminalCursorBlinkerRate(mActivity.getProperties().getTerminalCursorBlinkRate()))
                mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
            else
                Logger.logError(LOG_TAG,"Failed to start cursor blinker");
        } else {
            // Disable cursor blinker
            mActivity.getTerminalView().setTerminalCursorBlinkerState(false, true);
        }
    }



    public void shareSessionTranscript() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        String transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
        if (transcriptText == null) return;

        // See https://github.com/termux/termux-app/issues/1166.
        transcriptText = DataUtils.getTruncatedCommandOutput(transcriptText, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, false, true, false).trim();
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_transcript),
            transcriptText, mActivity.getString(R.string.title_share_transcript_with));
    }

    public void shareSelectedText() {
        String selectedText = mActivity.getTerminalView().getStoredSelectedText();
        if (DataUtils.isNullOrEmpty(selectedText)) return;
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_selected_text),
            selectedText, mActivity.getString(R.string.title_share_selected_text_with));
    }

    /**
     * The URLs that {@link #showUrlSelection()} lists for a session, keyed by what is listed, with the
     * address to copy or open as value, or null if the session has no terminal yet.
     */
    @Nullable
    public Map<String, String> collectUrlItems(@NonNull TerminalSession session) {
        TerminalEmulator emulator = session.getEmulator();
        if (emulator == null) return null;
        TerminalBuffer screen = emulator.getScreen();

        // Rows that the terminal wrapped itself are joined, the ones an application broke are joined
        // by extractUrls() if they look like one URL.
        // The key is what is listed, the value the address to copy or open.
        final Map<String, String> addressOfItem = new LinkedHashMap<>();
        for (CharSequence url : TermuxUrlUtils.extractUrls(screen.getTranscriptText(), emulator.mColumns))
            addressOfItem.put(url.toString(), url.toString());

        // The `OSC 8` hyperlinks come last, so that they are listed first. A link whose text is not its
        // address is listed with both, so that the address is known before it is copied or opened.
        for (TerminalHyperlink.Span span : screen.getHyperlinkSpans()) {
            String uri = span.link.getUri();
            String item = span.link.isLabelConcealingTarget(span.label)
                ? mActivity.getString(R.string.item_select_url_hyperlink, getLabelForDisplay(span.label), uri) : uri;
            if (!addressOfItem.containsKey(item)) addressOfItem.put(item, uri);
        }
        return addressOfItem;
    }

    public void showUrlSelection() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        final Map<String, String> addressOfItem = collectUrlItems(session);
        if (addressOfItem == null) return;
        List<CharSequence> items = new ArrayList<>(addressOfItem.keySet());

        if (items.isEmpty()) {
            DialogUtils.builder(mActivity, R.drawable.ui_ic_link, false).setTitle(R.string.title_select_url_none_found).show();
            return;
        }

        final CharSequence[] urls = items.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = DialogUtils.builder(mActivity, R.drawable.ui_ic_link, false).setItems(urls, (di, which) -> {
            String url = addressOfItem.get(urls[which].toString());
            ShareUtils.copyTextToClipboard(mActivity, url, mActivity.getString(R.string.msg_select_url_copied_to_clipboard));
        }).setTitle(R.string.title_select_url_dialog).create();

        // Long press to open URL:
        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String url = addressOfItem.get(urls[position].toString());
                ShareUtils.openUrl(mActivity, url);
                return true;
            });
        });

        dialog.show();
    }

    public void reportIssueFromTranscript() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        final String transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
        if (transcriptText == null) return;

        MessageDialogUtils.showMessage(mActivity, TermuxConstants.TERMUX_APP_NAME + " Report Issue",
            mActivity.getString(R.string.msg_add_termux_debug_info),
            mActivity.getString(dev.pschmitt.termux.shared.R.string.action_yes), (dialog, which) -> reportIssueFromTranscript(transcriptText, true),
            mActivity.getString(dev.pschmitt.termux.shared.R.string.action_no), (dialog, which) -> reportIssueFromTranscript(transcriptText, false),
            null);
    }

    private void reportIssueFromTranscript(String transcriptText, boolean addTermuxDebugInfo) {
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_generating_report), true);

        new Thread() {
            @Override
            public void run() {
                StringBuilder reportString = new StringBuilder();

                String title = TermuxConstants.TERMUX_APP_NAME + " Report Issue";

                reportString.append("## Transcript\n");
                reportString.append("\n").append(MarkdownUtils.getMarkdownCodeForString(transcriptText, true));
                reportString.append("\n##\n");

                if (addTermuxDebugInfo) {
                    reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                } else {
                    reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_PACKAGE));
                }

                reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(mActivity, true));

                if (TermuxBootstrap.isAppPackageManagerAPT()) {
                    String termuxAptInfo = TermuxUtils.geAPTInfoMarkdownString(mActivity);
                    if (termuxAptInfo != null)
                        reportString.append("\n\n").append(termuxAptInfo);
                }

                if (addTermuxDebugInfo) {
                    String termuxDebugInfo = TermuxUtils.getTermuxDebugMarkdownString(mActivity);
                    if (termuxDebugInfo != null)
                        reportString.append("\n\n").append(termuxDebugInfo);
                }

                String userActionName = UserAction.REPORT_ISSUE_FROM_TRANSCRIPT.getName();

                ReportInfo reportInfo = new ReportInfo(userActionName,
                    TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME, title);
                reportInfo.setReportString(reportString.toString());
                reportInfo.setReportStringSuffix("\n\n" + TermuxUtils.getReportIssueMarkdownString(mActivity));
                reportInfo.setReportSaveFileLabelAndPath(userActionName,
                    Environment.getExternalStorageDirectory() + "/" +
                        FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                ReportActivity.startReportActivity(mActivity, reportInfo);
            }
        }.start();
    }

    public void doPaste() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        if (!session.isRunning()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        if (text != null)
            session.getEmulator().paste(text);
    }

}
