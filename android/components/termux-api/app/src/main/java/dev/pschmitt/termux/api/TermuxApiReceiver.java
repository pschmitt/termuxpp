package dev.pschmitt.termux.api;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

import dev.pschmitt.termux.api.apis.AudioAPI;
import dev.pschmitt.termux.api.apis.BatteryStatusAPI;
import dev.pschmitt.termux.api.apis.BrightnessAPI;
import dev.pschmitt.termux.api.apis.CallLogAPI;
import dev.pschmitt.termux.api.apis.CameraInfoAPI;
import dev.pschmitt.termux.api.apis.CameraPhotoAPI;
import dev.pschmitt.termux.api.apis.ClipboardAPI;
import dev.pschmitt.termux.api.apis.ContactListAPI;
import dev.pschmitt.termux.api.apis.DialogAPI;
import dev.pschmitt.termux.api.apis.DownloadAPI;
import dev.pschmitt.termux.api.apis.FingerprintAPI;
import dev.pschmitt.termux.api.apis.InfraredAPI;
import dev.pschmitt.termux.api.apis.JobSchedulerAPI;
import dev.pschmitt.termux.api.apis.KeystoreAPI;
import dev.pschmitt.termux.api.apis.LocationAPI;
import dev.pschmitt.termux.api.apis.MediaPlayerAPI;
import dev.pschmitt.termux.api.apis.MediaScannerAPI;
import dev.pschmitt.termux.api.apis.MicRecorderAPI;
import dev.pschmitt.termux.api.apis.NfcAPI;
import dev.pschmitt.termux.api.apis.NotificationAPI;
import dev.pschmitt.termux.api.apis.NotificationListAPI;
import dev.pschmitt.termux.api.apis.SAFAPI;
import dev.pschmitt.termux.api.apis.SensorAPI;
import dev.pschmitt.termux.api.apis.ShareAPI;
import dev.pschmitt.termux.api.apis.SmsInboxAPI;
import dev.pschmitt.termux.api.apis.SmsSendAPI;
import dev.pschmitt.termux.api.apis.SpeechToTextAPI;
import dev.pschmitt.termux.api.apis.StorageGetAPI;
import dev.pschmitt.termux.api.apis.TelephonyAPI;
import dev.pschmitt.termux.api.apis.TextToSpeechAPI;
import dev.pschmitt.termux.api.apis.ToastAPI;
import dev.pschmitt.termux.api.apis.TorchAPI;
import dev.pschmitt.termux.api.apis.UsbAPI;
import dev.pschmitt.termux.api.apis.VibrateAPI;
import dev.pschmitt.termux.api.apis.VolumeAPI;
import dev.pschmitt.termux.api.apis.WallpaperAPI;
import dev.pschmitt.termux.api.apis.WifiAPI;
import dev.pschmitt.termux.api.activities.TermuxApiPermissionActivity;
import dev.pschmitt.termux.api.util.ResultReturner;
import dev.pschmitt.termux.shared.data.IntentUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.markdown.MarkdownUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.plugins.TermuxPluginUtils;

public class TermuxApiReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxApiReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        TermuxAPIApplication.setLogConfig(context, false);
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        try {
            doWork(context, intent);
        } catch (Throwable t) {
            String header = "Error in " + LOG_TAG + " for " + ResultReturner.getApiMethodLabel(intent) +
                    " sent by API server " + ResultReturner.getApiServerLabel(intent);
            String message = header + ":\n\n" +
                IntentUtils.getIntentString(intent) + "\n\n" +
                "Error";

            // Make sure never to throw exception from BroadCastReceiver to avoid "process is bad"
            // behaviour from the Android system.
            Logger.logStackTraceWithMessage(LOG_TAG, message, t);

            // Only add header to notification text.
            TermuxPluginUtils.sendPluginCommandErrorNotification(context, LOG_TAG,
                TermuxConstants.TERMUX_API_APP_NAME + " Error", header,
                MarkdownUtils.getMarkdownCodeForString(Logger.getMessageAndStackTraceString(message, t), true),
                false, false, true);

            ResultReturner.noteDone(this, intent);
        }
    }

    private void doWork(Context context, Intent intent) {
        String apiMethod = intent.getStringExtra("api_method");
        if (apiMethod == null) {
            Logger.logError(LOG_TAG, "Missing 'api_method' extra");
            return;
        }

        switch (apiMethod) {
            case "AudioInfo":
                AudioAPI.onReceive(this, context, intent);
                break;
            case "BatteryStatus":
                BatteryStatusAPI.onReceive(this, context, intent);
                break;
            case "Brightness":
                if (!Settings.System.canWrite(context)) {
                    TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.WRITE_SETTINGS);
                    Toast.makeText(context, "Please enable permission for Termux:API", Toast.LENGTH_LONG).show();

                    // user must enable WRITE_SETTINGS permission this special way
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    context.startActivity(settingsIntent);
                    return;
                }
                BrightnessAPI.onReceive(this, context, intent);
                break;
            case "CameraInfo":
                CameraInfoAPI.onReceive(this, context, intent);
                break;
            case "CameraPhoto":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CAMERA)) {
                    CameraPhotoAPI.onReceive(this, context, intent);
                }
                break;
            case "CallLog":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CALL_LOG)) {
                    CallLogAPI.onReceive(context, intent);
                }
                break;
            case "Clipboard":
                ClipboardAPI.onReceive(this, context, intent);
                break;
            case "ContactList":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CONTACTS)) {
                    ContactListAPI.onReceive(this, context, intent);
                }
                break;
            case "Dialog":
                DialogAPI.onReceive(context, intent);
                break;
            case "Download":
                DownloadAPI.onReceive(this, context, intent);
                break;
            case "Fingerprint":
                FingerprintAPI.onReceive(context, intent);
                break;
            case "InfraredFrequencies":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveCarrierFrequency(this, context, intent);
                }
                break;
            case "InfraredTransmit":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveTransmit(this, context, intent);
                }
                break;
            case "JobScheduler":
                JobSchedulerAPI.onReceive(this, context, intent);
                break;
            case "Keystore":
                KeystoreAPI.onReceive(this, intent);
                break;
            case "Location":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    LocationAPI.onReceive(this, context, intent);
                }
                break;
            case "MediaPlayer":
                MediaPlayerAPI.onReceive(context, intent);
                break;
            case "MediaScanner":
                MediaScannerAPI.onReceive(this, context, intent);
                break;
            case "MicRecorder":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    MicRecorderAPI.onReceive(context, intent);
                }
                break;
            case "Nfc":
                NfcAPI.onReceive(context, intent);
                break;
            case "NotificationList":
                NotificationListAPI.onReceive(this, context, intent);
                break;
            case "Notification":
                NotificationAPI.onReceiveShowNotification(this, context, intent);
                break;
            case "NotificationChannel":
                NotificationAPI.onReceiveChannel(this, context, intent);
                break;
            case "NotificationRemove":
                NotificationAPI.onReceiveRemoveNotification(this, context, intent);
                break;
            case "NotificationReply":
                NotificationAPI.onReceiveReplyToNotification(this, context, intent);
                break;
            case "SAF":
                SAFAPI.onReceive(this, context, intent);
                break;
            case "Sensor":
                SensorAPI.onReceive(context, intent);
                break;
            case "Share":
                ShareAPI.onReceive(this, context, intent);
                break;
            case "SmsInbox":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)) {
                    SmsInboxAPI.onReceive(this, context, intent);
                }
                break;
            case "SmsSend":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS)) {
                    SmsSendAPI.onReceive(this, context, intent);
                }
                break;
            case "StorageGet":
                StorageGetAPI.onReceive(this, context, intent);
                break;
            case "SpeechToText":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    SpeechToTextAPI.onReceive(context, intent);
                }
                break;
            case "TelephonyCall":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CALL_PHONE)) {
                    TelephonyAPI.onReceiveTelephonyCall(this, context, intent);
                }
                break;
            case "TelephonyCellInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    TelephonyAPI.onReceiveTelephonyCellInfo(this, context, intent);
                }
                break;
            case "TelephonyDeviceInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE)) {
                    TelephonyAPI.onReceiveTelephonyDeviceInfo(this, context, intent);
                }
                break;
            case "TextToSpeech":
                TextToSpeechAPI.onReceive(context, intent);
                break;
            case "Toast":
                ToastAPI.onReceive(context, intent);
                break;
            case "Torch":
                TorchAPI.onReceive(this, context, intent);
                break;
            case "Usb":
                UsbAPI.onReceive(context, intent);
                break;
            case "Vibrate":
                VibrateAPI.onReceive(this, context, intent);
                break;
            case "Volume":
                VolumeAPI.onReceive(this, context, intent);
                break;
            case "Wallpaper":
                WallpaperAPI.onReceive(context, intent);
                break;
            case "WifiConnectionInfo":
                WifiAPI.onReceiveWifiConnectionInfo(this, context, intent);
                break;
            case "WifiScanInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    WifiAPI.onReceiveWifiScanInfo(this, context, intent);
                }
                break;
            case "WifiEnable":
                WifiAPI.onReceiveWifiEnable(this, context, intent);
                break;
            default:
                Logger.logError(LOG_TAG, "Unrecognized 'api_method' extra: '" + apiMethod + "'");
        }
    }

}
