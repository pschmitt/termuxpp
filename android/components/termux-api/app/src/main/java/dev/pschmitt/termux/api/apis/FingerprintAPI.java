package dev.pschmitt.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonWriter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import androidx.fragment.app.FragmentActivity;

import dev.pschmitt.termux.api.util.ResultReturner;
import dev.pschmitt.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;

/**
 * This API allows users to use device fingerprint sensor as an authentication mechanism
 */
public class FingerprintAPI {

    protected static final String TAG             = "FingerprintAPI";
    protected static final String KEY_NAME        = "TermuxFingerprintAPIKey";
    protected static final String KEYSTORE_NAME   = "AndroidKeyStore";

    // milliseconds to wait before canceling
    protected static final int SENSOR_TIMEOUT = 30000;

    // maximum authentication attempts before locked out
    protected static final int MAX_ATTEMPTS   = 5;

    // error constants
    protected static final String ERROR_UNSUPPORTED_OS_VERSION    = "ERROR_UNSUPPORTED_OS_VERSION";
    protected static final String ERROR_NO_HARDWARE               = "ERROR_NO_HARDWARE";
    protected static final String ERROR_NO_ENROLLED_FINGERPRINTS  = "ERROR_NO_ENROLLED_FINGERPRINTS";
    protected static final String ERROR_KEY_GENERATOR             = "ERROR_KEY_GENERATOR";
    protected static final String ERROR_CIPHER                    = "ERROR_CIPHER";
    protected static final String ERROR_TIMEOUT                   = "ERROR_TIMEOUT";
    protected static final String ERROR_TOO_MANY_FAILED_ATTEMPTS  = "ERROR_TOO_MANY_FAILED_ATTEMPTS";
    protected static final String ERROR_LOCKOUT                   = "ERROR_LOCKOUT";

    // fingerprint authentication result constants
    protected static final String AUTH_RESULT_SUCCESS = "AUTH_RESULT_SUCCESS";
    protected static final String AUTH_RESULT_FAILURE = "AUTH_RESULT_FAILURE";
    protected static final String AUTH_RESULT_UNKNOWN = "AUTH_RESULT_UNKNOWN";



    // store result of fingerprint initialization / authentication
    protected static FingerprintResult fingerprintResult = new FingerprintResult();

    // have we posted our result back? The timeout cancels the prompt, which
    // reports an error of its own: only the first result may be returned.
    protected static final AtomicBoolean postedResult = new AtomicBoolean(false);


    private static final String LOG_TAG = "FingerprintAPI";

    /**
     * Handles setup of fingerprint sensor and writes Fingerprint result to console
     */
    public static void onReceive(final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        resetFingerprintResult();

        FingerprintManager fingerprintManager = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        // make sure we have a valid fingerprint sensor before attempting to launch Fingerprint activity
        if (validateFingerprintSensor(context, fingerprintManager)) {
            Intent fingerprintIntent = new Intent(context, FingerprintActivity.class);
            fingerprintIntent.putExtras(intent.getExtras());
            // Its own task opens over the terminal; skip the task-open slide,
            // the system prompt brings its own animation.
            fingerprintIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(fingerprintIntent);
        } else {
            postFingerprintResult(context, intent, fingerprintResult);
        }
    }

    /**
     * Writes the result of our fingerprint result to the console
     */
    protected static void postFingerprintResult(Context context, Intent intent, final FingerprintResult result) {
        if (!postedResult.compareAndSet(false, true)) {
            Logger.logDebug(LOG_TAG, "Fingerprint result already returned, ignoring " + result.errors);
            return;
        }
        ResultReturner.returnData(context, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();

                out.name("errors");
                out.beginArray();

                for (String error : result.errors) {
                    out.value(error);
                }
                out.endArray();

                out.name("failed_attempts").value(result.failedAttempts);
                out.name("auth_result").value(result.authResult);
                out.endObject();

                out.flush();
                out.close();
            }
        });
    }

    /**
     * Ensure that we have a fingerprint sensor and that the user has already enrolled fingerprints
     */
    protected static boolean validateFingerprintSensor(Context context, FingerprintManager fingerprintManager) {
        boolean result = true;

        if (fingerprintManager == null || !fingerprintManager.isHardwareDetected()) {
            Toast.makeText(context, "No fingerprint scanner found!", Toast.LENGTH_SHORT).show();
            appendFingerprintError(ERROR_NO_HARDWARE);
            result = false;
        }

        if (fingerprintManager == null || !fingerprintManager.hasEnrolledFingerprints()) {
            Toast.makeText(context, "No fingerprints enrolled", Toast.LENGTH_SHORT).show();
            appendFingerprintError(ERROR_NO_ENROLLED_FINGERPRINTS);
            result = false;
        }
        return result;
    }



    /**
     * Activity that is necessary for authenticating w/ fingerprint sensor
     */
    public static class FingerprintActivity extends FragmentActivity{

        private static final String LOG_TAG = "FingerprintActivity";

        @Override
        public void onCreate(Bundle savedInstanceState) {
            Logger.logDebug(LOG_TAG, "onCreate");

            super.onCreate(savedInstanceState);
            handleFingerprint();
        }

        /**
         * Handle setup and listening of fingerprint sensor
         */
        protected void handleFingerprint() {
            Executor executor = Executors.newSingleThreadExecutor();
            authenticateWithFingerprint(this, getIntent(), executor);
        }

        /**
         * Handles authentication callback from our fingerprint sensor
         */
        protected static void authenticateWithFingerprint(final FragmentActivity context, final Intent intent, final Executor executor) {
            BiometricPrompt biometricPrompt = new BiometricPrompt(context, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    Logger.logError(LOG_TAG, "Authentication error " + errorName(errorCode) + ": " + errString);
                    if (postedResult.get()) {
                        return;
                    }
                    appendFingerprintError(errorName(errorCode) + ": " + errString);
                    if (errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                        appendFingerprintError(ERROR_LOCKOUT);

                        // first time locked out, subsequent auth attempts will fail immediately for a bit
                        if (fingerprintResult.failedAttempts >= MAX_ATTEMPTS) {
                            appendFingerprintError(ERROR_TOO_MANY_FAILED_ATTEMPTS);
                        }
                    }
                    setAuthResult(AUTH_RESULT_FAILURE);
                    postFingerprintResult(context, intent, fingerprintResult);
                    finishPrompt(context);
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    setAuthResult(AUTH_RESULT_SUCCESS);
                    postFingerprintResult(context, intent, fingerprintResult);
                    finishPrompt(context);
                }

                @Override
                public void onAuthenticationFailed() {
                    addFailedAttempt();
                }
            });

            BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder();
            builder.setTitle(intent.hasExtra("title") ? intent.getStringExtra("title") : "Authenticate");
            builder.setNegativeButtonText(intent.hasExtra("cancel") ? intent.getStringExtra("cancel") : "Cancel");
            if (intent.hasExtra("description")) {
                builder.setDescription(intent.getStringExtra("description"));
            }
            if (intent.hasExtra("subtitle")) {
                builder.setSubtitle(intent.getStringExtra("subtitle"));
            }

            // listen to fingerprint sensor
            biometricPrompt.authenticate(builder.build());

            addSensorTimeout(context, intent, biometricPrompt);
        }

        /**
         * Adds a timeout for our fingerprint sensor which will force a result return if we
         * haven't already received one
         */
        protected static void addSensorTimeout(final FragmentActivity context, final Intent intent, final BiometricPrompt biometricPrompt) {
            final Handler timeoutHandler = new Handler(Looper.getMainLooper());
            timeoutHandler.postDelayed(() -> {
                if (!postedResult.get()) {
                    appendFingerprintError(ERROR_TIMEOUT);
                    setAuthResult(AUTH_RESULT_FAILURE);
                    // Return first: cancelling reports ERROR_CANCELED, which
                    // must not replace the timeout.
                    postFingerprintResult(context, intent, fingerprintResult);
                    biometricPrompt.cancelAuthentication();
                    finishPrompt(context);
                }
            }, SENSOR_TIMEOUT);
        }

        protected static void finishPrompt(final FragmentActivity context) {
            context.runOnUiThread(() -> {
                if (context.isFinishing()) return;
                context.finish();
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    context.overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
                } else {
                    context.overridePendingTransition(0, 0);
                }
            });
        }
    }

    /**
     * Clear out previous fingerprint result
     */
    protected static void resetFingerprintResult() {
        fingerprintResult = new FingerprintResult();
        postedResult.set(false);
    }

    /**
     * Name a BiometricPrompt error code, so callers see why it failed.
     */
    protected static String errorName(int errorCode) {
        switch (errorCode) {
            case BiometricPrompt.ERROR_HW_UNAVAILABLE: return "ERROR_HW_UNAVAILABLE";
            case BiometricPrompt.ERROR_UNABLE_TO_PROCESS: return "ERROR_UNABLE_TO_PROCESS";
            case BiometricPrompt.ERROR_TIMEOUT: return "ERROR_SENSOR_TIMEOUT";
            case BiometricPrompt.ERROR_NO_SPACE: return "ERROR_NO_SPACE";
            case BiometricPrompt.ERROR_CANCELED: return "ERROR_CANCELED";
            case BiometricPrompt.ERROR_LOCKOUT: return "ERROR_LOCKOUT";
            case BiometricPrompt.ERROR_VENDOR: return "ERROR_VENDOR";
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT: return "ERROR_LOCKOUT_PERMANENT";
            case BiometricPrompt.ERROR_USER_CANCELED: return "ERROR_USER_CANCELED";
            case BiometricPrompt.ERROR_NO_BIOMETRICS: return "ERROR_NO_BIOMETRICS";
            case BiometricPrompt.ERROR_HW_NOT_PRESENT: return "ERROR_HW_NOT_PRESENT";
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON: return "ERROR_NEGATIVE_BUTTON";
            case BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL: return "ERROR_NO_DEVICE_CREDENTIAL";
            case BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED: return "ERROR_SECURITY_UPDATE_REQUIRED";
            default: return "ERROR_" + errorCode;
        }
    }

    /**
     * Increment failed authentication attempts
     */
    protected static void addFailedAttempt() {
        fingerprintResult.failedAttempts++;
    }

    /**
     * Add an error to our fingerprint result
     */
    protected static void appendFingerprintError(String error) {
        fingerprintResult.errors.add(error);
    }

    /**
     * Set the final result of our authentication
     */
    protected static void setAuthResult(String authResult) {
        fingerprintResult.authResult = authResult;
    }


    /**
     * Simple class to encapsulate information about result of a fingerprint authentication attempt
     */
    static class FingerprintResult {
        public String authResult = AUTH_RESULT_UNKNOWN;
        public int failedAttempts = 0;
        public List<String> errors = new ArrayList<>();
    }
}
