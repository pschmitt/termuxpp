package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.view.WindowManager;

import com.termux.R;
import dev.pschmitt.termux.shared.file.FileUtils;
import dev.pschmitt.termux.shared.termux.crash.TermuxCrashUtils;
import dev.pschmitt.termux.shared.termux.file.TermuxFileUtils;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.interact.MessageDialogUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.markdown.MarkdownUtils;
import dev.pschmitt.termux.shared.errors.Error;
import dev.pschmitt.termux.shared.android.PackageUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.TermuxUtils;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

import static dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                refreshHelperHookInBackground(activity.getApplicationContext());
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        final BootstrapProgressOverlay progress = BootstrapProgressOverlay.show(activity);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // The fork identity needs the helper rewrite table. Load it before anything is deleted:
                    // when it is missing or unusable nothing was touched and the error says why.
                    final TermuxBootstrapAdapter adapter;
                    try {
                        adapter = TermuxBootstrapAdapter.load(TermuxConstants.TERMUX_PACKAGE_NAME, helperIdentityValues());
                    } catch (java.io.IOException e) {
                        Logger.logError(LOG_TAG, "Refusing to install the bootstrap: " + e.getMessage());
                        activity.runOnUiThread(() -> progress.dismiss(null));
                        showBootstrapErrorDialog(activity, whenDone, "The bootstrap was not installed and nothing was changed: "
                            + e.getMessage());
                        return;
                    }

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        activity.runOnUiThread(() -> progress.dismiss(null));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        activity.runOnUiThread(() -> progress.dismiss(null));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        activity.runOnUiThread(() -> progress.dismiss(null));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        activity.runOnUiThread(() -> progress.dismiss(null));
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    // Extraction and the stock to app rewrite are one unit: any failure throws here, before
                    // the staging directory is moved to the prefix, so no half adapted prefix is ever activated.
                    final TermuxBootstrapExtractor.Result extracted = TermuxBootstrapExtractor.extract(
                        loadZipBytes(), TERMUX_STAGING_PREFIX_DIR, adapter,
                        (file, mode) -> {
                            try {
                                Os.chmod(file.getAbsolutePath(), mode);
                            } catch (android.system.ErrnoException e) {
                                throw new java.io.IOException("chmod failed for " + file + ": " + e.getMessage(), e);
                            }
                        });

                    if (!adapter.isStock()) {
                        File homeProfile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".profile");
                        if (!homeProfile.exists()) {
                            ensureDirectoryExists(homeProfile.getParentFile());
                            try (FileOutputStream outStream = new FileOutputStream(homeProfile)) {
                                outStream.write("if [ -r \"$PREFIX/etc/profile\" ]; then\n    . \"$PREFIX/etc/profile\"\nfi\n"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }

                        // Keep the helpers correct after APT replaces them with the stock versions. Fatal:
                        // without the hook the first apt upgrade of termux-tools breaks login. A slim
                        // bootstrap without dpkg has no APT and nothing for the hook to keep correct.
                        if (new File(TERMUX_STAGING_PREFIX_DIR, "var/lib/dpkg").isDirectory())
                            TermuxHelperRewrites.deployHook(TERMUX_STAGING_PREFIX_DIR,
                                TermuxConstants.TERMUX_PREFIX_DIR_PATH, helperIdentityValues());
                        Logger.logInfo(LOG_TAG, "Adapted " + extracted.adaptedFiles.size() + " bootstrap files to "
                            + adapter.dataDir() + ".");
                    }

                    for (TermuxBootstrapExtractor.Symlink symlink : extracted.symlinks) {
                        Os.symlink(symlink.target, symlink.link);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    // A provisioning bootstrap: set up the Nix guest while the card reports its progress, so
                    // the first session enters it right away. If this does not finish, nixpp login resumes it.
                    if (TermuxNix.shouldEnterGuest(properties)) {
                        // Sessions start nixpp, not the native login shell whose profile.d fallback would
                        // run the bootstrap second stage (postinst scripts, SSH host keys): run it here.
                        activity.runOnUiThread(() -> {
                            progress.addNixSteps();
                            progress.note(activity.getString(R.string.bootstrap_note_extracted,
                                extracted.symlinks.size(), extracted.adaptedFiles.size()));
                            progress.step(BootstrapProgressOverlay.Step.CONFIGURE);
                        });
                        TermuxNixSetup.runSecondStage(activity);
                        TermuxNixSetup.run(activity, TermuxNix.user(properties),
                            status -> activity.runOnUiThread(() -> showNixStatus(progress, status)));
                    }

                    activity.runOnUiThread(() -> progress.finish(whenDone));

                } catch (final Exception e) {
                    activity.runOnUiThread(() -> progress.dismiss(null));
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                }
            }
        }.start();
    }

    /** Show one Nix setup step on the first-start card. */
    private static void showNixStatus(BootstrapProgressOverlay progress, TermuxNixSetup.Status status) {
        switch (status.stage) {
            case EXTRACT:
            case PREPARED:
                progress.step(BootstrapProgressOverlay.Step.NIX_PREPARE);
                break;
            case PACKAGE:
                progress.packages(status.index, status.total, status.name, status.detail);
                break;
            case CHECK:
                progress.step(BootstrapProgressOverlay.Step.NIX_CHECK);
                break;
            default:
                break;
        }
    }

    /**
     * An app update can come with a new helper rewrite table. Deploy it into the existing prefix and run the
     * hook, so login and the helpers get the new rows without waiting for an apt run.
     * Never fatal: the old files keep working, the problem is logged.
     */
    private static void refreshHelperHookInBackground(final android.content.Context context) {
        new Thread("termuxpp-helper-hook") {
            @Override
            public void run() {
                try {
                    // A slim prefix has no APT (and no bash to run the hook with).
                    if (!new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/apt").exists()) return;
                    Map<String, String> values = helperIdentityValues();
                    if (TermuxBootstrapAdapter.load(TermuxConstants.TERMUX_PACKAGE_NAME, values).isStock()) return;

                    File workDir = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, "usr.hook-refresh");
                    boolean changed = TermuxHelperRewrites.refreshHook(workDir, TERMUX_PREFIX_DIR,
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH, values);

                    // Run the hook on every start, not only after a refresh: it is idempotent and cheap, and a
                    // run that failed once (or an apt that replaced a helper) is retried on the next start.
                    Logger.logInfo(LOG_TAG, "Running the helper hook" + (changed ? " (the rewrite table changed)." : "."));
                    String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
                    ProcessBuilder builder = new ProcessBuilder(prefix + "/bin/bash", prefix + "/" + TermuxHelperRewrites.HOOK_SCRIPT);
                    // The same environment as a Termux shell: the fork binaries need LD_LIBRARY_PATH and the
                    // pkg-redirect preload, they do not carry a runpath.
                    builder.environment().clear();
                    builder.environment().putAll(
                        new dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment().getEnvironment(context, false));
                    builder.redirectErrorStream(true);
                    Process process = builder.start();
                    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    try (java.io.InputStream in = process.getInputStream()) {
                        while ((read = in.read(buffer)) > 0) captured.write(buffer, 0, read);
                    }
                    String output = new String(captured.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    int exit = process.waitFor();
                    if (exit == 0) Logger.logInfo(LOG_TAG, "The helper hook ran: " + output.trim());
                    else Logger.logError(LOG_TAG, "The helper hook failed with exit " + exit + ": " + output.trim());
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Could not refresh the helper hook: " + e);
                }
            }
        }.start();
    }

    /** The fork identity values for the placeholders of the helper rewrite table. */
    static Map<String, String> helperIdentityValues() {
        Map<String, String> values = new HashMap<>();
        values.put("PACKAGE", TermuxConstants.TERMUX_PACKAGE_NAME);
        values.put("RELOAD_STYLE_ACTION", TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        values.put("WAKE_LOCK_ACTION", TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK);
        values.put("WAKE_UNLOCK_ACTION", TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK);
        values.put("DATA_DIR", TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
        values.put("REDIRECT_SO_VAR", "TERMUX_PKG_REDIRECT_SO");
        // The canonical config directory relative to the home directory, like ".config/termuxpp".
        values.put("CONFIG_DIR_NAME", TermuxConstants.TERMUX_CONFIG_TERMUXPP_DIR_PATH.substring(TermuxConstants.TERMUX_HOME_DIR_PATH.length() + 1));
        // The motd of the termux-tools package: this app and its documentation, not the upstream site.
        values.put("PRODUCT_NAME", "TermuxPP");
        values.put("REPO_URL", "https://github.com/pschmitt/termuxpp");
        values.put("DOCS_URL", "https://github.com/pschmitt/termuxpp/tree/main/docs/en");
        values.put("UPSTREAM_PACKAGES_ISSUES_URL", "https://github.com/termux/termux-packages/issues");
        values.put("FORK_ONLY", "fork");
        return values;
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                DialogUtils.builder(activity, R.drawable.ui_ic_warning, true)
                    .setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
