package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.event.SystemEventReceiver;
import com.termux.app.nativecli.NativeCliInstaller;
import com.termux.app.nativecli.NativeCliUpdater;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalSessionServiceClient;
import com.termux.app.terminal.GestureCommandLaunch;
import com.termux.app.terminal.SessionReorder;
import dev.pschmitt.termux.shared.termux.plugins.TermuxPluginUtils;
import dev.pschmitt.termux.shared.data.IntentUtils;
import dev.pschmitt.termux.shared.net.uri.UriUtils;
import dev.pschmitt.termux.shared.errors.Errno;
import dev.pschmitt.termux.shared.shell.ShellUtils;
import dev.pschmitt.termux.shared.shell.command.runner.app.AppShell;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import dev.pschmitt.termux.shared.termux.shell.TermuxShellUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.shell.TermuxShellManager;
import dev.pschmitt.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import dev.pschmitt.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.notification.NotificationUtils;
import dev.pschmitt.termux.shared.android.PermissionUtils;
import dev.pschmitt.termux.shared.data.DataUtils;
import dev.pschmitt.termux.shared.shell.command.ExecutionCommand;
import dev.pschmitt.termux.shared.shell.command.ExecutionCommand.Runner;
import dev.pschmitt.termux.shared.shell.command.ExecutionCommand.ShellCreateMode;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A service holding a list of {@link TermuxSession} in {@link TermuxShellManager#mTermuxSessions} and background {@link AppShell}
 * in {@link TermuxShellManager#mTermuxTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link TermuxActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link TermuxActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public final class TermuxService extends Service implements AppShell.AppShellClient, TermuxSession.TermuxSessionClient {

    /** This service is only bound from inside the same process and never uses IPC. */
    class LocalBinder extends Binder {
        public final TermuxService service = TermuxService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();

    @Nullable private NetworkStatePublisher networkStatePublisher;
    @Nullable private PermissionStatePublisher permissionStatePublisher;


    /** The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    private TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
    private final TmuxWindows tmuxWindows = new TmuxWindows(mHandler, () -> {
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();
    });

    /** The basic implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that does not hold activity references and only a service reference.
     */
    private final TermuxTerminalSessionServiceClient mTermuxTerminalSessionServiceClient = new TermuxTerminalSessionServiceClient(this);

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * Termux app shell manager
     */
    private TermuxShellManager mShellManager;

    /** The wake lock, and with wake-lock-wifi the Wi-Fi lock, are acquired and released together. */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    /** When the wake lock was acquired (wall clock, for the notification's timer). */
    private long mWakeLockSince;
    /** Releases the wake lock after wake-lock-timeout minutes. */
    private final Runnable mWakeLockTimeout = () -> {
        Logger.logInfo(LOG_TAG, "Releasing the wake lock after wake-lock-timeout");
        actionReleaseWakeLock(true);
    };

    /** If the user has executed the {@link TERMUX_SERVICE#ACTION_STOP_SERVICE} intent. */
    boolean mWantsToStop = false;

    private SessionCommandServer sessionCommandServer;
    private int sessionCommandServerAttempts;
    private final Runnable retrySessionCommandServer = this::startSessionCommandServer;
    private boolean legacySessionCliRemoved;
    private final Set<String> closingSessions = new HashSet<>();

    private static final String LOG_TAG = "TermuxService";
    private static final int SESSION_COMMAND_SERVER_MAX_ATTEMPTS = 10;
    private static final long SESSION_COMMAND_SERVER_RETRY_DELAY_MS = 1000;

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");

        // Get Termux app SharedProperties without loading from disk since TermuxApplication handles
        // load and TermuxActivity handles reloads
        mProperties = TermuxAppSharedProperties.getProperties();

        mShellManager = TermuxShellManager.getShellManager();

        runStartForeground();
        startSessionCommandServer();
        removeLegacySessionCli();
        NativeCliUpdater.installAsync(this);

        SystemEventReceiver.registerPackageUpdateEvents(this);

        // Lets the guest supervisor follow Wi-Fi for services.<name>.allowedSsids.
        networkStatePublisher = new NetworkStatePublisher(this);
        networkStatePublisher.start();
        // Lets termuxpp-setup-permissions report the grants without Shizuku.
        permissionStatePublisher = new PermissionStatePublisher(this);
        permissionStatePublisher.start();
        NixppRuntimeRecovery.startWatchdog(this);
    }

    private void startSessionCommandServer() {
        sessionCommandServerAttempts++;
        try {
            sessionCommandServer = new SessionCommandServer(this);
        } catch (IOException e) {
            if (sessionCommandServerAttempts < SESSION_COMMAND_SERVER_MAX_ATTEMPTS) {
                mHandler.postDelayed(retrySessionCommandServer, SESSION_COMMAND_SERVER_RETRY_DELAY_MS);
            } else {
                Logger.logWarn(LOG_TAG, "Could not start the session command listener: " + e.getMessage());
            }
        }
    }

    /** Republish the permission state, e.g. after returning from a permission prompt. */
    public void refreshPermissionState() {
        if (permissionStatePublisher != null) permissionStatePublisher.refreshSoon();
    }

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        String action = null;
        if (intent != null) {
            Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));
            action = intent.getAction();
        }

        if (action != null) {
            switch (action) {
                case TERMUX_SERVICE.ACTION_STOP_SERVICE:
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received");
                    actionStopService();
                    break;
                case TERMUX_SERVICE.ACTION_KILL:
                    Logger.logInfo(LOG_TAG, "ACTION_KILL intent received");
                    actionKill(intent);
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_LOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_UNLOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                    break;
                case TERMUX_SERVICE.ACTION_SERVICE_EXECUTE:
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                    break;
                default:
                    Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"");
                    break;
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy");

        if (networkStatePublisher != null) {
            networkStatePublisher.stop();
            networkStatePublisher = null;
        }
        if (permissionStatePublisher != null) {
            permissionStatePublisher.stop();
            permissionStatePublisher = null;
        }
        NixppRuntimeRecovery.stopWatchdog();

        mHandler.removeCallbacks(retrySessionCommandServer);
        if (sessionCommandServer != null) {
            try {
                sessionCommandServer.close();
            } catch (IOException e) {
                Logger.logWarn(LOG_TAG, "Could not close the session command listener: " + e.getMessage());
            }
        }

        TermuxShellUtils.clearTermuxTMPDIR(true);

        actionReleaseWakeLock(false);
        if (!mWantsToStop)
            killAllTermuxExecutionCommands();

        TermuxShellManager.onAppExit(this);

        SystemEventReceiver.unregisterPackageUpdateEvents(this);

        runStopForeground();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onUnbind");

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTermuxTerminalSessionActivityClient != null)
            unsetTermuxTerminalSessionClient();
        return false;
    }

    /** Make service run in foreground mode. */
    private void runStartForeground() {
        setupNotificationChannel();
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
    }

    /** Make service leave foreground mode. */
    private void runStopForeground() {
        stopForeground(true);
    }

    /** Request to stop service. */
    private void requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service");
        runStopForeground();
        stopSelf();
    }

    /** Stop all shells like the exit action, then kill the whole app process. */
    private void actionKill(Intent intent) {
        boolean restart = intent != null && intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_KILL_RESTART, false);
        if (restart) scheduleRelaunch();
        actionStopService();
        TermuxShellUtils.clearTermuxTMPDIR(true);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Ask AlarmManager to relaunch the main activity shortly after the process has died. */
    private void scheduleRelaunch() {
        try {
            Intent launch = new Intent(this, TermuxActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, launch,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to schedule relaunch after kill", e);
        }
    }

    /** Process action to stop service. */
    private void actionStopService() {
        mWantsToStop = true;
        killAllTermuxExecutionCommands();
        requestStopService();
    }

    /** Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    private synchronized void killAllTermuxExecutionCommands() {
        boolean processResult;

        Logger.logDebug(LOG_TAG, "Killing TermuxSessions=" + mShellManager.mTermuxSessions.size() +
            ", TermuxTasks=" + mShellManager.mTermuxTasks.size() +
            ", PendingPluginExecutionCommands=" + mShellManager.mPendingPluginExecutionCommands.size());

        List<TermuxSession> termuxSessions = new ArrayList<>(mShellManager.mTermuxSessions);
        List<AppShell> termuxTasks = new ArrayList<>(mShellManager.mTermuxTasks);
        List<ExecutionCommand> pendingPluginExecutionCommands = new ArrayList<>(mShellManager.mPendingPluginExecutionCommands);

        for (int i = 0; i < termuxSessions.size(); i++) {
            ExecutionCommand executionCommand = termuxSessions.get(i).getExecutionCommand();
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult();
            termuxSessions.get(i).killIfExecuting(this, processResult);
            if (!processResult)
                mShellManager.mTermuxSessions.remove(termuxSessions.get(i));
        }


        for (int i = 0; i < termuxTasks.size(); i++) {
            ExecutionCommand executionCommand = termuxTasks.get(i).getExecutionCommand();
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                termuxTasks.get(i).killIfExecuting(this, true);
            else
                mShellManager.mTermuxTasks.remove(termuxTasks.get(i));
        }

        for (int i = 0; i < pendingPluginExecutionCommands.size(); i++) {
            ExecutionCommand executionCommand = pendingPluginExecutionCommands.get(i);
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), this.getString(dev.pschmitt.termux.shared.R.string.error_execution_cancelled))) {
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);
                }
            }
        }
    }



    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermuxConstants.TERMUX_APP_NAME.toLowerCase() + ":service-wakelock");
        mWakeLock.acquire();
        mWakeLockSince = System.currentTimeMillis();

        // The high-performance Wi-Fi lock keeps the radio out of power save, which costs battery
        // for as long as it is held: only with wake-lock-wifi.
        if (mProperties != null && mProperties.shouldWakeLockHoldWifi()) {
            // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TermuxConstants.TERMUX_APP_NAME.toLowerCase());
            mWifiLock.acquire();
        }

        int timeoutMinutes = mProperties == null ? 0 : mProperties.getWakeLockTimeoutMinutes();
        mHandler.removeCallbacks(mWakeLockTimeout);
        if (timeoutMinutes > 0) mHandler.postDelayed(mWakeLockTimeout, timeoutMinutes * 60_000L);

        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this);
        }

        updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully");

    }

    /** Whether the wake lock is held, for the terminal menu. */
    public boolean isWakeLockHeld() {
        return mWakeLock != null;
    }

    /** Acquire or release the wake lock, like the notification's button. */
    public void toggleWakeLock() {
        if (mWakeLock == null) actionAcquireWakeLock();
        else actionReleaseWakeLock(true);
    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private void actionReleaseWakeLock(boolean updateNotification) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks");

        mHandler.removeCallbacks(mWakeLockTimeout);
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        if (updateNotification)
            updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully");
    }

    /** Process {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask. */
    private void actionServiceExecute(Intent intent) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute");
            return;
        }

        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId());

        executionCommand.executableUri = intent.getData();
        executionCommand.isPluginExecutionCommand = true;

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RUNNER,
            (intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false) ? Runner.APP_SHELL.getName() : Runner.TERMINAL_SESSION.getName()));
        if (Runner.runnerOf(executionCommand.runner) == null) {
            String errmsg = this.getString(R.string.error_termux_service_invalid_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return;
        }

        if (executionCommand.executableUri != null) {
            Logger.logVerbose(LOG_TAG, "uri: \"" + executionCommand.executableUri + "\", path: \"" + executionCommand.executableUri.getPath() + "\", fragment: \"" + executionCommand.executableUri.getFragment() + "\"");

            // Get full path including fragment (anything after last "#")
            executionCommand.executable = UriUtils.getUriFilePathWithFragment(executionCommand.executableUri);
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null);
            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null);
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null);
        executionCommand.isFailsafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_NAME, null);
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, null);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null);
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        if (executionCommand.shellCreateMode == null)
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.getMode();

        // Add the execution command to pending plugin execution commands list
        mShellManager.mPendingPluginExecutionCommands.add(executionCommand);

        if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
            executeTermuxTaskCommand(executionCommand);
        else if (Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner))
            executeTermuxSessionCommand(executionCommand);
        else {
            String errmsg = getString(R.string.error_termux_service_unsupported_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
        }
    }





    /** Execute a shell command in background TermuxTask. */
    private void executeTermuxTaskCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        AppShell newTermuxTask = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxTask = getTermuxTaskForShellName(executionCommand.shellName);
            if (newTermuxTask != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newTermuxTask == null)
            newTermuxTask = createTermuxTask(executionCommand);
    }

    /** Create a TermuxTask. */
    @Nullable
    public AppShell createTermuxTask(String executablePath, String[] arguments, String stdin, String workingDirectory) {
        return createTermuxTask(new ExecutionCommand(TermuxShellManager.getNextShellId(), executablePath,
            arguments, stdin, workingDirectory, Runner.APP_SHELL.getName(), false));
    }

    /** Create a TermuxTask. */
    @Nullable
    public synchronized AppShell createTermuxTask(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask");

        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxTask()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        AppShell newTermuxTask = AppShell.execute(this, executionCommand, this,
            new TermuxShellEnvironment(), null,false);
        if (newTermuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxTask command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mTermuxTasks.add(newTermuxTask);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        updateNotification();

        return newTermuxTask;
    }

    /** Callback received when a TermuxTask finishes. */
    @Override
    public void onAppShellExited(final AppShell termuxTask) {
        mHandler.post(() -> {
            if (termuxTask != null) {
                ExecutionCommand executionCommand = termuxTask.getExecutionCommand();

                Logger.logVerbose(LOG_TAG, "The onTermuxTaskExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

                mShellManager.mTermuxTasks.remove(termuxTask);
            }

            updateNotification();
        });
    }





    /** Execute a shell command in a foreground {@link TermuxSession}. */
    private void executeTermuxSessionCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        TermuxSession newTermuxSession = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxSession = getTermuxSessionForShellName(executionCommand.shellName);
            if (newTermuxSession != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newTermuxSession == null)
            newTermuxSession = createTermuxSession(executionCommand);
        if (newTermuxSession == null) return;

        handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction,
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
            newTermuxSession.getTerminalSession());
    }

    /** Start user-configured gesture shell text in a new visible session without logging it. */
    public void executeGestureCommand(@Nullable String command, @NonNull GestureCommandContext context) {
        if (command == null || command.trim().isEmpty() || mWantsToStop) return;
        mHandler.post(() -> {
            if (mWantsToStop) return;

            boolean nixIsProvisioned = TermuxNix.isProvisioned();
            boolean sessionStartsInNix = TermuxNix.shouldEnterGuest(mProperties);
            GestureCommandLaunch launch = GestureCommandLaunch.create(command, context,
                sessionStartsInNix, nixIsProvisioned, TermuxNix.NIXPP_PATH,
                TermuxConstants.TERMUX_PREFIX_DIR_PATH, mProperties.getNixUser());
            if (launch == null) {
                if (context == GestureCommandContext.NIX && !nixIsProvisioned)
                    Logger.showToast(this, "The Nix environment is not provisioned", true);
                return;
            }

            ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId(),
                launch.executable, launch.arguments, null, null, Runner.TERMINAL_SESSION.getName(), false);
            executionCommand.shellName = "Gesture command";
            executionCommand.commandLabel = "Gesture command";
            executionCommand.redactArgumentsInLogs = true;
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.getMode();
            executionCommand.sessionAction = String.valueOf(
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY);

            TermuxSession session = createTermuxSession(executionCommand);
            if (session != null)
                handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY,
                    session.getTerminalSession());
        });
    }

    /**
     * Create a {@link TermuxSession}.
     * Currently called by {@link TermuxTerminalSessionActivityClient#addNewSession(boolean, String)} to add a new {@link TermuxSession}.
     */
    @Nullable
    public TermuxSession createTermuxSession(String executablePath, String[] arguments, String stdin,
                                             String workingDirectory, boolean isFailSafe, String sessionName) {
        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            executablePath, arguments, stdin, workingDirectory, Runner.TERMINAL_SESSION.getName(), isFailSafe);
        executionCommand.shellName = sessionName;
        return createTermuxSession(executionCommand);
    }

    /** Create a {@link TermuxSession}. */
    @Nullable
    public synchronized TermuxSession createTermuxSession(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;
        removeLegacySessionCli();
        NativeCliUpdater.installAsync(this);

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession");

        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxSession()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;
        executionCommand.terminalTranscriptRows = mProperties.getTerminalTranscriptRows();
        executionCommand.useLibghostty = mProperties.shouldUseLibghostty();

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        TermuxSession newTermuxSession = TermuxSession.execute(this, executionCommand, getTermuxTerminalSessionClient(),
            this, new TermuxShellEnvironment(), null, executionCommand.isPluginExecutionCommand);
        if (newTermuxSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxSession command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mTermuxSessions.add(newTermuxSession);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();

        updateNotification();

        // No need to recreate the activity since it likely just started and theme should already have applied
        TermuxActivity.updateTermuxActivityStyling(this, false);

        return newTermuxSession;
    }

    /** Remove a TermuxSession. */
    public synchronized int removeTermuxSession(TerminalSession sessionToRemove) {
        int index = getIndexOfSession(sessionToRemove);
        tmuxWindows.remove(sessionToRemove.mHandle);
        closingSessions.remove(sessionToRemove.mHandle);

        if (index >= 0)
            mShellManager.mTermuxSessions.get(index).finish();

        return index;
    }

    /** Callback received when a {@link TermuxSession} finishes. */
    @Override
    public void onTermuxSessionExited(final TermuxSession termuxSession) {
        if (termuxSession != null) {
            ExecutionCommand executionCommand = termuxSession.getExecutionCommand();

            Logger.logVerbose(LOG_TAG, "The onTermuxSessionExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

            mShellManager.mTermuxSessions.remove(termuxSession);
            termuxSession.getTerminalSession().closeEmulator();

            // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();
        }

        updateNotification();
    }





    private ShellCreateMode processShellCreateMode(@NonNull ExecutionCommand executionCommand) {
        if (ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode))
            return ShellCreateMode.ALWAYS; // Default
        else if (ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode))
            if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                TermuxPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                    getString(R.string.error_termux_service_execution_command_shell_name_unset, executionCommand.shellCreateMode));
                return null;
            } else {
               return ShellCreateMode.NO_SHELL_WITH_NAME;
            }
        else {
            TermuxPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                getString(R.string.error_termux_service_unsupported_execution_command_shell_create_mode, executionCommand.shellCreateMode));
            return null;
        }
    }

    /**
     * Removes the files that earlier app versions wrote for the Java/Python {@code tpp-session}: the
     * launcher script, the {@code tpp-session} script and its plugin copy, and the completions. The
     * command is now {@code tpp session} in the native {@code tpp} binary, which {@link NativeCliUpdater}
     * installs. That installer leaves a path it did not create
     * alone, so the old files must go first. Only regular files that carry the marks of the old
     * installation are removed; anything the user put there stays.
     */
    private void removeLegacySessionCli() {
        if (legacySessionCliRemoved) return;
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!new File(prefix, "bin").isDirectory()) return;
        String[][] legacy = {
            {"bin/tpp-session", "libexec/tpp/session-launcher"},
            {"libexec/tpp/plugins/session", "libexec/tpp/session-launcher"},
            {"libexec/tpp/session-launcher", "Native tpp-session backend"},
            {"share/bash-completion/completions/tpp-session", "tpp-session"},
            {"share/fish/vendor_completions.d/tpp-session.fish", "tpp-session"},
            {"share/zsh/site-functions/_tpp-session", "tpp-session"},
        };
        // Paths that the native installer recorded are its own files (the new completions have the
        // same names and similar text as the old ones), never leftovers.
        JSONObject recorded = null;
        try {
            File state = new File(prefix, NativeCliInstaller.STATE_FILE);
            if (state.isFile())
                recorded = new JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8))
                    .optJSONObject("files");
        } catch (IOException | JSONException e) {
            Logger.logWarn(LOG_TAG, "Could not read the native install record: " + e.getMessage());
        }
        for (String[] entry : legacy) {
            File file = new File(prefix, entry[0]);
            try {
                if (recorded != null && recorded.has(entry[0])) continue;
                if (!isLegacySessionFile(file, entry[1])) continue;
                if (!file.delete()) Logger.logWarn(LOG_TAG, "Could not remove the old " + file);
            } catch (IOException e) {
                Logger.logWarn(LOG_TAG, "Could not check the old " + file + ": " + e.getMessage());
            }
        }
        legacySessionCliRemoved = true;
    }

    /** True for a regular file, never a link, whose first 64 KiB contain {@code mark}. */
    private static boolean isLegacySessionFile(File file, String mark) throws IOException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) return false;
        byte[] head = new byte[64 * 1024];
        int length = 0;
        try (InputStream source = new FileInputStream(file)) {
            int count;
            while (length < head.length && (count = source.read(head, length, head.length - length)) != -1)
                length += count;
        }
        return new String(head, 0, length, StandardCharsets.ISO_8859_1).contains(mark);
    }

    private void openTerminalUi(String action, @Nullable String query) {
        if (mTermuxTerminalSessionActivityClient == null
            || !mTermuxTerminalSessionActivityClient.openTerminalUi(action, query))
            throw new IllegalStateException("Cannot open " + action + " from the current TermuxPP screen");
    }

    public JSONArray getTmuxWindows(TerminalSession session) {
        return session.isRunning() ? tmuxWindows.get(session.mHandle) : new JSONArray();
    }

    boolean isTmuxSwitchPending(TerminalSession session, String id) {
        return tmuxWindows.isSwitchPending(session.mHandle, id);
    }

    TmuxWindows.Selection selectTmuxWindow(TerminalSession session, String windowId,
                                           TmuxWindows.SwitchResult result) {
        if (!session.isRunning()) return TmuxWindows.Selection.UNAVAILABLE;
        TmuxWindows.Selection selection = tmuxWindows.select(session.mHandle, windowId, result);
        // The plugin's tmux takes the choice as keys typed into the terminal, wherever tmux runs.
        if (selection == TmuxWindows.Selection.SWITCH_REQUESTED && tmuxWindows.isReported(session.mHandle))
            session.write(TmuxWindows.SELECT_KEYS + windowId.substring(1) + "\r");
        return selection;
    }

    /** A window report of the TermuxPP tmux plugin in a session's output, or null when its client has gone. */
    public void onTmuxWindowsReport(TerminalSession session, @Nullable String report) {
        tmuxWindows.report(session.mHandle, report);
    }

    /** Called on a socket thread: hand the helper of an attachment its chosen window as soon as there is one. */
    Object awaitTmuxSelection(JSONArray args) throws JSONException, InterruptedException {
        if (args.length() != 2) throw new IllegalArgumentException("Invalid tmux wait arguments");
        String token = args.getString(1);
        if (!token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid attachment token");
        String id = tmuxWindows.awaitSelection(token, SessionCommandServer.WAIT_MS);
        return id == null ? JSONObject.NULL : id;
    }

    /** Called on the main thread by the app-UID session socket. */
    Object runSessionCommand(JSONArray args) throws JSONException, ErrnoException {
        String command = args.getString(0);
        if ("tmux-windows".equals(command) && (args.length() == 3 || args.length() == 4)) {
            TerminalSession terminal = getTerminalSessionForHandle(args.getString(1));
            if (terminal == null || !terminal.isRunning()) throw new IllegalArgumentException("Terminal session has ended");
            return tmuxWindows.update(terminal.mHandle, args);
        }
        if ("new".equals(command) && args.length() == 3)
            return createSessionFromCommand(args.isNull(1) ? null : args.getString(1), args.getJSONArray(2));
        if ("reload-settings".equals(command) && args.length() == 1) {
            mProperties.loadTermuxPropertiesFromDisk();
            // Also marks the reload as pending: a stopped activity has no receiver and would miss it.
            TermuxActivity.onPropertiesEditedFromSettings(this);
            return JSONObject.NULL;
        }
        if ("switcher".equals(command) && args.length() == 1) {
            openTerminalUi("session-switcher", null);
            return JSONObject.NULL;
        }
        if ("ui".equals(command)) {
            String action = args.getString(1);
            int count = args.length();
            if (count != 2 && !(count == 3 && "settings".equals(action)))
                throw new IllegalArgumentException("Invalid terminal UI command arguments");
            openTerminalUi(action, count == 3 ? args.getString(2) : null);
            return JSONObject.NULL;
        }
        List<TermuxSession> sessions = getTermuxSessions();
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
        String current = preferences == null ? null : preferences.getCurrentSession();
        TerminalSession selected = mTermuxTerminalSessionActivityClient == null
            ? (current == null ? null : getTerminalSessionForHandle(current))
            : mTermuxTerminalSessionActivityClient.getCurrentSession();
        if (selected == null && !sessions.isEmpty())
            selected = sessions.get(sessions.size() - 1).getTerminalSession();

        if ("list".equals(command) && args.length() == 1) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < sessions.size(); i++) {
                TerminalSession terminal = sessions.get(i).getTerminalSession();
                result.put(new JSONObject()
                    .put("id", terminal.mHandle)
                    .put("position", i + 1)
                    .put("name", terminal.mSessionName == null ? "" : terminal.mSessionName)
                    .put("title", terminal.getTitle() == null ? "" : terminal.getTitle())
                    .put("running", terminal.isRunning())
                    .put("current", terminal == selected));
            }
            return result;
        }

        int expected = "rename".equals(command) || "move".equals(command) ? 3 : 2;
        if (args.length() != expected) throw new IllegalArgumentException("Invalid session command arguments");
        TerminalSession terminal = getTerminalSessionForHandle(args.getString(1));
        if (terminal == null) throw new IllegalArgumentException("Session ID not found");
        int index = getIndexOfSession(terminal);

        switch (command) {
            case "focus":
                selectSessionFromCommand(terminal);
                break;
            case "rename":
                String name = args.getString(2);
                if (name.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Names cannot contain control characters");
                terminal.mSessionName = name;
                sessions.get(index).getExecutionCommand().shellName = name;
                break;
            case "move":
                int position = args.getInt(2);
                if (position < 1 || position > sessions.size())
                    throw new IllegalArgumentException("Position must be between 1 and " + sessions.size());
                sessions.add(position - 1, sessions.remove(index));
                if (mTermuxTerminalSessionActivityClient != null && selected != null)
                    mTermuxTerminalSessionActivityClient.checkAndScrollToSession(selected);
                break;
            case "close":
                if (terminal.getPid() > 0) {
                    signalSession(terminal, OsConstants.SIGHUP);
                    closingSessions.add(terminal.mHandle);
                } else if (terminal.isRunning()) {
                    throw new IllegalStateException("Session shell has not started");
                } else {
                    dismissSessionFromCommand(terminal);
                }
                break;
            case "kill":
                signalSession(terminal, OsConstants.SIGKILL);
                break;
            default:
                throw new IllegalArgumentException("Unknown session command");
        }
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();
        return JSONObject.NULL;
    }

    private String createSessionFromCommand(@Nullable String name, JSONArray argv) throws JSONException {
        if (mWantsToStop) throw new IllegalStateException("TermuxPP is stopping");
        if (name != null && name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Names cannot contain control characters");

        String executable = null;
        List<String> arguments = new ArrayList<>();
        if (argv.length() != 0) {
            if (argv.getString(0).isEmpty())
                throw new IllegalArgumentException("Expected a command after --");
            if (TermuxNix.shouldEnterGuest(mProperties)) {
                executable = TermuxNix.NIXPP_PATH;
                java.util.Collections.addAll(arguments, TermuxNix.loginArguments(TermuxNix.user(mProperties)));
                arguments.add("--");
            } else {
                executable = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/sh";
                java.util.Collections.addAll(arguments, "-c", "exec \"$@\"", "tpp");
            }
            for (int i = 0; i < argv.length(); i++) {
                String arg = argv.getString(i);
                if (arg.indexOf(0) >= 0)
                    throw new IllegalArgumentException("Command arguments cannot contain NUL");
                arguments.add(arg);
            }
        }

        ExecutionCommand execution = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            executable, arguments.toArray(new String[0]), null, null, Runner.TERMINAL_SESSION.getName(), false);
        execution.shellName = name;
        execution.redactArgumentsInLogs = true;
        TermuxSession session = createTermuxSession(execution);
        if (session == null) throw new IllegalStateException("Could not create a new terminal session");
        TerminalSession terminal = session.getTerminalSession();
        handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, terminal);
        return terminal.mHandle;
    }

    public boolean finishSessionClosedByCommand(TerminalSession terminal) {
        if (!closingSessions.remove(terminal.mHandle)) return false;
        dismissSessionFromCommand(terminal);
        return true;
    }

    private void dismissSessionFromCommand(TerminalSession terminal) {
        TermuxTerminalSessionActivityClient activityClient = mTermuxTerminalSessionActivityClient;
        if (activityClient != null && terminal == activityClient.getCurrentSession()) {
            activityClient.removeFinishedSession(terminal);
            return;
        }
        int index = removeTermuxSession(terminal);
        if (index < 0 || activityClient != null) return;

        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
        if (preferences == null || !terminal.mHandle.equals(preferences.getCurrentSession())) return;
        TermuxSession next = getTermuxSession(Math.min(index, getTermuxSessionsSize() - 1));
        preferences.setCurrentSession(next == null ? null : next.getTerminalSession().mHandle);
    }

    private void selectSessionFromCommand(TerminalSession terminal) {
        handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY, terminal);
    }

    private void signalSession(TerminalSession terminal, int signal) throws ErrnoException {
        int pid = terminal.getPid();
        if (pid <= 0) throw new IllegalStateException("Session has no running shell");
        Os.kill(pid, signal);
    }

    /** Process session action for new session. */
    private void handleSessionAction(int sessionAction, TerminalSession newTerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"");

        switch (sessionAction) {
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                break;
            default:
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.");
                handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession);
                break;
        }
    }

    /** Launch the {@link }TermuxActivity} to bring it to foreground. */
    private void startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TermuxActivity.startTermuxActivity(this);
        } else {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
            if (preferences == null) return;
            if (preferences.arePluginErrorNotificationsEnabled(false))
                Logger.showToast(this, this.getString(R.string.error_display_over_other_apps_permission_not_granted_to_start_terminal), true);
        }
    }





    /** If {@link TermuxActivity} has not bound to the {@link TermuxService} yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the {@link #mTermuxTerminalSessionServiceClient}. Once {@link TermuxActivity} bind
     * callback is received, it should call {@link #setTermuxTerminalSessionClient} to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} so that further terminal sessions are directly
     * passed the {@link TermuxTerminalSessionActivityClient} object which fully implements the
     * {@link TerminalSessionClient} interface.
     *
     * @return Returns the {@link TermuxTerminalSessionActivityClient} if {@link TermuxActivity} has bound with
     * {@link TermuxService}, otherwise {@link TermuxTerminalSessionServiceClient}.
     */
    public synchronized TermuxTerminalSessionClientBase getTermuxTerminalSessionClient() {
        if (mTermuxTerminalSessionActivityClient != null)
            return mTermuxTerminalSessionActivityClient;
        else
            return mTermuxTerminalSessionServiceClient;
    }

    /** This should be called when {@link TermuxActivity#onServiceConnected} is called to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} variable and update the {@link TerminalSession}
     * and {@link TerminalEmulator} clients in case they were passed {@link TermuxTerminalSessionServiceClient}
     * earlier.
     *
     * @param termuxTerminalSessionActivityClient The {@link TermuxTerminalSessionActivityClient} object that fully
     * implements the {@link TerminalSessionClient} interface.
     */
    public synchronized void setTermuxTerminalSessionClient(TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)
            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    /** This should be called when {@link TermuxActivity} has been destroyed and in {@link #onUnbind(Intent)}
     * so that the {@link TermuxService} and {@link TerminalSession} and {@link TerminalEmulator}
     * clients do not hold an activity references.
     */
    public synchronized void unsetTermuxTerminalSessionClient() {
        unsetTermuxTerminalSessionClient(null);
    }

    /**
     * Like {@link #unsetTermuxTerminalSessionClient()}, but only if {@code client} is still the
     * current one. A destroyed activity must not take the sessions away from a newer one that has
     * already connected (recreation, relaunch): its terminal would stop updating, finished sessions
     * would not be reported to it, and its session list would miss changes and crash.
     *
     * @param client The client of the caller, or {@code null} to unset whatever client is set.
     */
    public synchronized void unsetTermuxTerminalSessionClient(@Nullable TermuxTerminalSessionActivityClient client) {
        if (client != null && client != mTermuxTerminalSessionActivityClient) {
            Logger.logDebug(LOG_TAG, "Not unsetting the session client of a destroyed activity: a newer one is set");
            return;
        }
        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)
            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionServiceClient);

        mTermuxTerminalSessionActivityClient = null;
    }





    private Notification buildNotification() {
        Resources res = getResources();

        // Set pending intent to be launched when notification is clicked
        Intent notificationIntent = TermuxActivity.newInstance(this);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);


        // Set notification text
        int sessionCount = getTermuxSessionsSize();
        int taskCount = mShellManager.mTermuxTasks.size();
        String notificationText = sessionCount + " session" + (sessionCount == 1 ? "" : "s");
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + (taskCount == 1 ? "" : "s");
        }

        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) notificationText += " (wake lock held)";


        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        int priority = (wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;


        // Build the notification
        Notification.Builder builder =  NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, priority,
            TermuxConstants.TERMUX_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null)  return null;

        // While a wake lock is held, a timer shows for how long, so a forgotten one stands out.
        // The system runs it: the notification is not updated for it.
        if (wakeLockHeld) {
            builder.setWhen(mWakeLockSince);
            builder.setShowWhen(true);
            builder.setUsesChronometer(true);
        } else {
            builder.setShowWhen(false);
        }

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        // TermuxSessions are always ongoing
        builder.setOngoing(true);


        // Set Exit button action
        Intent exitIntent = new Intent(this, TermuxService.class).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, 0));


        // Set Wakelock button actions
        String newWakeAction = wakeLockHeld ? TERMUX_SERVICE.ACTION_WAKE_UNLOCK : TERMUX_SERVICE.ACTION_WAKE_LOCK;
        Intent toggleWakeLockIntent = new Intent(this, TermuxService.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0));


        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    private synchronized void updateNotification() {
        if (mWakeLock == null && mShellManager.mTermuxSessions.isEmpty() && mShellManager.mTermuxTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService();
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
        }
    }





    private void setCurrentStoredTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return;
        // Make the newly created session the current one to be displayed
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
        if (preferences == null) return;
        preferences.setCurrentSession(terminalSession.mHandle);
    }

    public synchronized boolean isTermuxSessionsEmpty() {
        return mShellManager.mTermuxSessions.isEmpty();
    }

    public synchronized int getTermuxSessionsSize() {
        return mShellManager.mTermuxSessions.size();
    }

    public synchronized List<TermuxSession> getTermuxSessions() {
        return mShellManager.mTermuxSessions;
    }

    /**
     * Move a session to another position of the session list, shifting those in between. The list order is
     * what the sidebar shows and what the next and previous session actions follow.
     *
     * @return Whether the order changed.
     */
    public synchronized boolean moveTermuxSession(int from, int to) {
        return SessionReorder.move(mShellManager.mTermuxSessions, from, to);
    }

    @Nullable
    public synchronized TermuxSession getTermuxSession(int index) {
        if (index >= 0 && index < mShellManager.mTermuxSessions.size())
            return mShellManager.mTermuxSessions.get(index);
        else
            return null;
    }

    @Nullable
    public synchronized TermuxSession getTermuxSessionForTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return null;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++) {
            if (mShellManager.mTermuxSessions.get(i).getTerminalSession().equals(terminalSession))
                return mShellManager.mTermuxSessions.get(i);
        }

        return null;
    }

    public synchronized TermuxSession getLastTermuxSession() {
        return mShellManager.mTermuxSessions.isEmpty() ? null : mShellManager.mTermuxSessions.get(mShellManager.mTermuxSessions.size() - 1);
    }

    public synchronized int getIndexOfSession(TerminalSession terminalSession) {
        if (terminalSession == null) return -1;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++) {
            if (mShellManager.mTermuxSessions.get(i).getTerminalSession().equals(terminalSession))
                return i;
        }
        return -1;
    }

    public synchronized TerminalSession getTerminalSessionForHandle(String sessionHandle) {
        TerminalSession terminalSession;
        for (int i = 0, len = mShellManager.mTermuxSessions.size(); i < len; i++) {
            terminalSession = mShellManager.mTermuxSessions.get(i).getTerminalSession();
            if (terminalSession.mHandle.equals(sessionHandle))
                return terminalSession;
        }
        return null;
    }

    public synchronized AppShell getTermuxTaskForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        AppShell appShell;
        for (int i = 0, len = mShellManager.mTermuxTasks.size(); i < len; i++) {
            appShell = mShellManager.mTermuxTasks.get(i);
            String shellName = appShell.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return appShell;
        }
        return null;
    }

    public synchronized TermuxSession getTermuxSessionForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        TermuxSession termuxSession;
        for (int i = 0, len = mShellManager.mTermuxSessions.size(); i < len; i++) {
            termuxSession = mShellManager.mTermuxSessions.get(i);
            String shellName = termuxSession.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return termuxSession;
        }
        return null;
    }



    public boolean wantsToStop() {
        return mWantsToStop;
    }

}
