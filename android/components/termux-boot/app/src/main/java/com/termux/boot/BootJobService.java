package com.termux.boot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.util.Log;

public class BootJobService extends JobService {

    public static final String SCRIPT_FILE_PATH = "dev.pschmitt.termux.boot.script_path";

    private static final String TAG = "termux";

    // Keep the Java namespace independent from the TermuxPP Android application ID.
    private static final String TERMUX_PACKAGE_NAME = "dev.pschmitt.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.TermuxService";
    private static final String ACTION_EXECUTE = TERMUX_PACKAGE_NAME + ".service_execute";
    private static final String URI_SCHEME_SERVICE_EXECUTE = TERMUX_PACKAGE_NAME + ".file";
    private static final String EXTRA_EXECUTE_IN_BACKGROUND = TERMUX_PACKAGE_NAME + ".execute.background";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Executing job " + params.getJobId() + ".");

        PersistableBundle extras = params.getExtras();
        String filePath = extras.getString(SCRIPT_FILE_PATH);

        Uri scriptUri = new Uri.Builder().scheme(URI_SCHEME_SERVICE_EXECUTE).path(filePath).build();
        Intent executeIntent = new Intent(ACTION_EXECUTE, scriptUri);
        executeIntent.setClassName(TERMUX_PACKAGE_NAME, TERMUX_SERVICE);
        executeIntent.putExtra(EXTRA_EXECUTE_IN_BACKGROUND, true);

        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // https://developer.android.com/about/versions/oreo/background.html
            context.startForegroundService(executeIntent);
        } else {
            context.startService(executeIntent);
        }

        return false; // offloaded to Termux; job is done
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "Execution of job " + params.getJobId() + " has been cancelled.");
        return false; // do not reschedule
    }
}
