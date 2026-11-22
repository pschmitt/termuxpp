package com.termux.app.fragments.settings;

import android.app.Activity;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.preference.Preference;

import com.termux.BuildConfig;
import com.google.android.material.color.MaterialColors;
import com.termux.R;
import com.termux.app.settings.GroupedPreferencesFragment;
import com.termux.app.update.InstalledBuild;
import com.termux.app.update.ShizukuInstaller;
import com.termux.app.update.ShizukuState;
import com.termux.app.update.UpdateChannel;
import com.termux.app.update.UpdateController;
import com.termux.app.update.UpdateFailure;
import com.termux.app.update.UpdateState;
import com.termux.app.update.UpdateTarget;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.logger.Logger;

import rikka.shizuku.Shizuku;

/**
 * Updates the integrated TermuxPP APK. Opening this screen only reads local state.
 */
@Keep
public class UpdatesPreferencesFragment extends GroupedPreferencesFragment implements UpdateController.Listener {

    private static final String LOG_TAG = "UpdatesPreferencesFragment";
    private static final UpdateTarget APP = UpdateTarget.CORE;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> mainHandler.post(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> mainHandler.post(this::refreshShizuku);
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        ShizukuInstaller.onPermissionResult(grantResult);
        mainHandler.post(this::refreshShizuku);
    };

    private UpdateController controller;
    @Nullable private SwipeRefreshLayout swipeRefreshLayout;
    private boolean shizukuListening;
    @Nullable private CharSequence previousTitle;
    /** Shizuku may be installed while this screen is open. */
    @Nullable private Boolean shizukuIconInstalled;
    private boolean appIconSet;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.updates_preferences, rootKey);
        controller = UpdateController.getInstance(context);

        Preference shizuku = findPreference("update_shizuku");
        if (shizuku != null) {
            shizuku.setOnPreferenceClickListener(p -> {
                onShizukuClicked();
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = getContext();
        if (context == null || !(view.getParent() instanceof android.view.ViewGroup)) return;

        SwipeRefreshLayout refresh = new SwipeRefreshLayout(context);
        refresh.setColorSchemeColors(MaterialColors.getColor(context, R.attr.termuxUiPrimary, ContextCompat.getColor(context, R.color.ui_primary)));
        refresh.setOnRefreshListener(() -> {
            if (controller == null || controller.isBusy()) {
                refresh.setRefreshing(false);
                return;
            }
            checkForUpdates();
            renderAll();
        });
        android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
        int index = parent.indexOfChild(view);
        android.view.ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        parent.removeView(view);
        parent.addView(refresh, index, layoutParams);
        refresh.addView(view);
        android.view.View list = getListView();
        refresh.setOnChildScrollUpCallback((layout, child) -> list.canScrollVertically(-1));
        swipeRefreshLayout = refresh;
    }

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = getActivity();
        if (activity != null) {
            previousTitle = activity.getTitle();
            activity.setTitle(R.string.termux_updates_title);
        }
        appIconSet = false;
        shizukuIconInstalled = null;
        controller.addListener(this);
        try {
            Shizuku.addBinderReceivedListener(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permissionResult);
            shizukuListening = true;
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Could not listen to Shizuku: " + t);
        }
        controller.refreshInstalled();
        refreshShizuku();
    }

    @Override
    public void onDestroyView() {
        SwipeRefreshLayout refresh = swipeRefreshLayout;
        if (refresh != null) {
            refresh.setOnRefreshListener(null);
            refresh.setOnChildScrollUpCallback(null);
            if (refresh.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) refresh.getParent()).removeView(refresh);
            }
            swipeRefreshLayout = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onStop() {
        controller.removeListener(this);
        if (shizukuListening) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceived);
                Shizuku.removeBinderDeadListener(binderDead);
                Shizuku.removeRequestPermissionResultListener(permissionResult);
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Could not stop listening to Shizuku: " + t);
            }
            shizukuListening = false;
        }
        Activity activity = getActivity();
        if (activity != null && previousTitle != null) activity.setTitle(previousTitle);
        super.onStop();
    }

    @Override
    public void onUpdateStateChanged(@NonNull UpdateTarget target, @NonNull UpdateState state) {
        renderAll();
    }

    private void checkForUpdates() {
        controller.checkAll(UpdateChannel.LATEST);
    }

    private void confirmUpdate() {
        Context context = getContext();
        UpdateState state = controller.getState(APP);
        if (context == null || controller.isBusy() || state.offer == null
                || state.phase != UpdateState.Phase.AVAILABLE) return;
        if (ShizukuInstaller.state(context) != ShizukuState.READY) {
            onShizukuClicked();
            return;
        }
        String name = getString(R.string.termux_update_app_core);
        DialogUtils.builder(context, R.drawable.ui_ic_system_update, false)
                .setTitle(getString(R.string.termux_update_confirm_title, name))
                .setMessage(getString(R.string.termux_update_confirm_message, name, state.offer.shortCommit()))
                .setPositiveButton(R.string.termux_update_confirm_positive,
                        (dialog, which) -> controller.startUpdate(APP))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onShizukuClicked() {
        Context context = getContext();
        if (context == null) return;
        switch (ShizukuInstaller.state(context)) {
            case PERMISSION_NEEDED:
                ShizukuInstaller.requestPermission();
                break;
            case READY:
            case UNAVAILABLE:
                break;
            default:
                Intent intent = ShizukuInstaller.managerLaunchIntent(context);
                if (intent == null) break;
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Logger.logError(LOG_TAG, "Could not open Shizuku: " + e);
                }
                break;
        }
    }

    private void refreshShizuku() {
        Context context = getContext();
        Preference shizuku = findPreference("update_shizuku");
        if (context == null || shizuku == null) return;
        shizuku.setSummary(shizukuSummary(ShizukuInstaller.state(context)));
        updateShizukuIcon(context, shizuku);
        renderAll();
    }

    private static int shizukuSummary(ShizukuState state) {
        switch (state) {
            case UNSUPPORTED: return R.string.termux_update_shizuku_unsupported;
            case NOT_RUNNING: return R.string.termux_update_shizuku_not_running;
            case PERMISSION_NEEDED: return R.string.termux_update_shizuku_permission_needed;
            case PERMISSION_DENIED: return R.string.termux_update_shizuku_permission_denied;
            case READY: return R.string.termux_update_shizuku_ready;
            case UNAVAILABLE:
            default: return R.string.termux_update_shizuku_unavailable;
        }
    }

    private void renderAll() {
        Context context = getContext();
        if (context == null || controller == null) return;

        UpdateState state = controller.getState(APP);
        boolean busy = controller.isBusy();
        SwipeRefreshLayout refresh = swipeRefreshLayout;
        if (refresh != null) {
            refresh.setEnabled(!busy || state.phase == UpdateState.Phase.CHECKING);
            refresh.setRefreshing(state.phase == UpdateState.Phase.CHECKING);
        }

        UpdateAppPreference app = findPreference("update_app_core");
        if (app == null) return;
        if (!appIconSet) {
            app.setIcon(appIcon(context, APP, true));
            appIconSet = true;
        }
        boolean available = state.phase == UpdateState.Phase.AVAILABLE && state.offer != null;
        boolean ready = available && ShizukuInstaller.state(context) == ShizukuState.READY;
        String summary = installedSummary() + "\n" + statusText(context, state);
        if (available && !ready)
            summary += "\n" + getString(R.string.termux_update_app_shizuku_needed);
        app.setSummary(summary);

        if (busy) {
            boolean cancellable = controller.isCancellable();
            app.render(state, cancellable ? R.string.termux_update_cancel : 0,
                    cancellable ? controller::cancel : null);
        } else if (ready) {
            app.render(state, R.string.termux_update_confirm_positive, this::confirmUpdate);
        } else {
            app.render(state, R.string.termux_update_check, this::checkForUpdates);
        }
    }

    /** The Shizuku row shows the Shizuku manager's own icon when it is installed. */
    private void updateShizukuIcon(@NonNull Context context, @NonNull Preference shizuku) {
        boolean installed = isInstalled(context, ShizukuInstaller.MANAGER_PACKAGE);
        if (shizukuIconInstalled != null && shizukuIconInstalled == installed) return;
        shizukuIconInstalled = installed;
        shizuku.setIcon(installedIconOrStandIn(context, ShizukuInstaller.MANAGER_PACKAGE, installed,
            R.drawable.ui_ic_power));
    }

    private static boolean isInstalled(@NonNull Context context, @NonNull String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    @NonNull
    static Drawable appIcon(@NonNull Context context, @NonNull UpdateTarget target, boolean installed) {
        return installedIconOrStandIn(context, target.packageName, installed, R.drawable.ui_ic_terminal);
    }

    @NonNull
    static Drawable installedIconOrStandIn(@NonNull Context context, @NonNull String packageName,
                                           boolean installed, @DrawableRes int glyph) {
        if (installed) {
            try {
                Drawable icon = context.getPackageManager().getApplicationIcon(packageName);
                if (icon != null) return icon;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Fall through to the stand-in.
            }
        }
        return standInIcon(context, glyph);
    }

    @NonNull
    private static Drawable standInIcon(@NonNull Context context, @DrawableRes int glyphRes) {
        Drawable container = ContextCompat.getDrawable(context, R.drawable.ui_icon_container);
        Drawable glyph = ContextCompat.getDrawable(context, glyphRes);
        if (container == null || glyph == null) throw new IllegalStateException("Missing bundled icon");
        glyph = DrawableCompat.wrap(glyph.mutate());
        DrawableCompat.setTint(glyph, MaterialColors.getColor(context, R.attr.termuxUiOnContainer, ContextCompat.getColor(context, R.color.ui_on_container)));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{container.mutate(), glyph});
        int inset = Math.round(8 * context.getResources().getDisplayMetrics().density);
        layers.setLayerInset(1, inset, inset, inset, inset);
        // Dimmed: the app is missing.
        layers.setAlpha(128);
        return layers;
    }

    @NonNull
    private String installedSummary() {
        InstalledBuild build = InstalledBuild.parse(BuildConfig.GIT_REVISION);
        if (!build.isComparable() || build.commit == null) {
            return getString(R.string.termux_update_app_installed_unknown);
        }
        String shortCommit = build.commit.length() > 7 ? build.commit.substring(0, 7) : build.commit;
        return getString(R.string.termux_update_app_installed, shortCommit);
    }

    @NonNull
    private String statusText(@NonNull Context context, @NonNull UpdateState state) {
        switch (state.phase) {
            case CHECKING:
                return getString(R.string.termux_update_status_checking);
            case UP_TO_DATE:
                return getString(R.string.termux_update_status_up_to_date);
            case UNAVAILABLE:
                return getString(R.string.termux_update_status_unavailable);
            case AVAILABLE:
                return state.offer == null ? "" : getString(R.string.termux_update_status_available,
                        state.offer.shortCommit(), Formatter.formatShortFileSize(context, state.offer.apk.size));
            case NOT_ELIGIBLE:
                return getString(R.string.termux_update_status_not_eligible);
            case CHECK_FAILED:
                return getString(R.string.termux_update_status_check_failed, state.detail == null ? "?" : state.detail);
            case DOWNLOADING:
                return state.total > 0
                        ? getString(R.string.termux_update_status_downloading,
                                Formatter.formatShortFileSize(context, state.done),
                                Formatter.formatShortFileSize(context, state.total))
                        : getString(R.string.termux_update_status_downloading_unknown,
                                Formatter.formatShortFileSize(context, state.done));
            case VERIFYING:
                return getString(R.string.termux_update_status_verifying);
            case INSTALLING:
                return getString(R.string.termux_update_status_installing);
            case FAILED:
                return failureText(state);
            case IDLE:
            default:
                return getString(R.string.termux_update_status_idle);
        }
    }

    @NonNull
    private String failureText(@NonNull UpdateState state) {
        String reason = getString(failureString(state.failure));
        if (state.failure == UpdateFailure.CANCELLED) return reason;
        String text = state.detail == null ? reason
                : getString(R.string.termux_update_status_failed_detail, reason, state.detail);
        return getString(R.string.termux_update_status_failed, text);
    }

    private static int failureString(@Nullable UpdateFailure failure) {
        if (failure == null) return R.string.termux_update_failure_install_failed;
        switch (failure) {
            case NO_APK: return R.string.termux_update_failure_no_apk;
            case NOT_TRUSTED: return R.string.termux_update_failure_not_trusted;
            case SHIZUKU_NOT_READY: return R.string.termux_update_failure_shizuku_not_ready;
            case DOWNLOAD_FAILED: return R.string.termux_update_failure_download_failed;
            case INSUFFICIENT_STORAGE: return R.string.termux_update_failure_insufficient_storage;
            case CHECKSUM_UNAVAILABLE: return R.string.termux_update_failure_checksum_unavailable;
            case CHECKSUM_MISMATCH: return R.string.termux_update_failure_checksum_mismatch;
            case UNREADABLE_APK: return R.string.termux_update_failure_unreadable_apk;
            case WRONG_PACKAGE: return R.string.termux_update_failure_wrong_package;
            case SHARED_USER_MISMATCH: return R.string.termux_update_failure_shared_user_mismatch;
            case SIGNATURE_MISMATCH: return R.string.termux_update_failure_signature_mismatch;
            case DOWNGRADE: return R.string.termux_update_failure_downgrade;
            case CANCELLED: return R.string.termux_update_failure_cancelled;
            case INSTALL_FAILED:
            default: return R.string.termux_update_failure_install_failed;
        }
    }

}
