package com.termux.boot;

import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BootReceiver extends BroadcastReceiver {

    public static final int TERMUX_BOOT_JOB_ID_BASE = 1000;
    static int jobId = TERMUX_BOOT_JOB_ID_BASE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        @SuppressLint("SdCardPath") final String TERMUX_HOME = "/data/data/dev.pschmitt.termux/files/home";
        List<File> files = getBootScripts(TERMUX_HOME);

        StringBuilder logMessage = new StringBuilder();
        for (File file : files) {
            if (!file.isFile()) continue;

            if (logMessage.length() > 0) logMessage.append(", ");
            logMessage.append(file.getName());

            ensureFileReadableAndExecutable(file);

            PersistableBundle extras = new PersistableBundle();
            extras.putString(BootJobService.SCRIPT_FILE_PATH, file.getAbsolutePath());

            ComponentName serviceComponent = new ComponentName(context, BootJobService.class);
            JobInfo job = new JobInfo.Builder(jobId++, serviceComponent)
                    .setExtras(extras)
                    .setOverrideDeadline(3 * 1000)
                    .build();
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            assert jobScheduler != null;
            jobScheduler.schedule(job);
        }

        if (logMessage.length() > 0) {
            Log.i("termux", "Executed files at boot: " + logMessage);
        } else {
            Log.i("termux", "No files to execute at boot");
        }
    }

    public static List<File> getBootScripts(String termuxHome) {
        String[] relativeDirectories = {
                ".config/termuxpp/boot",
                ".config/termux/boot",
                ".termux/boot"
        };
        Map<String, File> scriptsByName = new LinkedHashMap<>();

        for (String relativeDirectory : relativeDirectories) {
            File directory = new File(termuxHome, relativeDirectory);
            File[] files = directory.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.isFile() && !scriptsByName.containsKey(file.getName()))
                    scriptsByName.put(file.getName(), file);
            }
        }

        List<File> scripts = new ArrayList<>(scriptsByName.values());
        Collections.sort(scripts, (f1, f2) -> f1.getName().compareTo(f2.getName()));
        return scripts;
    }

    /** Ensure readable and executable file if user forgot to do so. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void ensureFileReadableAndExecutable(File file) {
        if (!file.canRead()) file.setReadable(true);
        if (!file.canExecute()) file.setExecutable(true);
    }
}
