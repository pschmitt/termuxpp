package dev.pschmitt.termux.api.apis;

import android.content.ClipData;
import android.content.ClipData.Item;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.text.TextUtils;

import dev.pschmitt.termux.api.TermuxApiReceiver;
import dev.pschmitt.termux.api.util.ResultReturner;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.clipboard.ClipboardImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class ClipboardAPI {

    private static final String LOG_TAG = "ClipboardAPI";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData clipData = clipboard.getPrimaryClip();

        if ("3".equals(intent.getStringExtra("api_version"))) {
            returnImage(apiReceiver, context, intent, clipData);
            return;
        }

        boolean version2 = "2".equals(intent.getStringExtra("api_version"));
        if (version2) {
            boolean set = intent.getBooleanExtra("set", false);
            if (set) {
                ResultReturner.returnData(apiReceiver, intent, new ResultReturner.WithStringInput() {
                    @Override
                    protected boolean trimInput() {
                        return false;
                    }

                    @Override
                    public void writeResult(PrintWriter out) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", inputString));
                    }
                });
            } else {
                ResultReturner.returnData(apiReceiver, intent, out -> {
                    if (clipData == null) {
                        out.print("");
                    } else {
                        int itemCount = clipData.getItemCount();
                        for (int i = 0; i < itemCount; i++) {
                            Item item = clipData.getItemAt(i);
                            CharSequence text = item.coerceToText(context);
                            if (!TextUtils.isEmpty(text)) {
                                out.print(text);
                            }
                        }
                    }
                });
            }
        } else {
            final String newClipText = intent.getStringExtra("text");
            if (newClipText != null) {
                // Set clip.
                clipboard.setPrimaryClip(ClipData.newPlainText("", newClipText));
            }

            ResultReturner.returnData(apiReceiver, intent, out -> {
                if (newClipText == null) {
                    // Get clip.
                    if (clipData == null) {
                        out.print("");
                    } else {
                        int itemCount = clipData.getItemCount();
                        for (int i = 0; i < itemCount; i++) {
                            Item item = clipData.getItemAt(i);
                            CharSequence text = item.coerceToText(context);
                            if (!TextUtils.isEmpty(text)) {
                                out.print(text);
                            }
                        }
                    }
                }
            });
        }
    }

    private static void returnImage(TermuxApiReceiver receiver, Context context, Intent intent, ClipData clip) {
        ResultReturner.returnData(receiver, intent, new ResultReturner.BinaryOutput() {
            @Override
            public void writeResult(OutputStream out) throws IOException {
                out.write("\0TPPIMG1\n".getBytes(StandardCharsets.US_ASCII));
                ClipDescription description = clip == null ? null : clip.getDescription();
                PersistableBundle extras = description == null ? null : description.getExtras();
                if (extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) {
                    writeImageHeader(out, "unavailable", 0);
                    return;
                }

                ByteArrayOutputStream imageBytes = new ByteArrayOutputStream(64 * 1024);
                ClipboardImage image;
                try {
                    image = ClipboardImage.find(clip, context.getContentResolver());
                    if (image != null) {
                        ClipboardImage.copy(context.getContentResolver(), image.uri, imageBytes);
                    }
                } catch (IOException | SecurityException e) {
                    writeImageHeader(out, "unreadable", 0);
                    return;
                }

                if (image == null) {
                    writeImageHeader(out, "unavailable", 0);
                    return;
                }
                if (imageBytes.size() == 0) {
                    writeImageHeader(out, "unreadable", 0);
                    return;
                }
                writeImageHeader(out, image.mimeType, imageBytes.size());
                imageBytes.writeTo(out);
            }
        });
    }

    private static void writeImageHeader(OutputStream out, String mimeType, int length) throws IOException {
        out.write((mimeType + "\n" + length + "\n").getBytes(StandardCharsets.UTF_8));
    }

}
