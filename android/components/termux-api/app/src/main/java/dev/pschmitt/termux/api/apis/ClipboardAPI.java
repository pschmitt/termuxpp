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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class ClipboardAPI {

    private static final String LOG_TAG = "ClipboardAPI";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData clipData = clipboard.getPrimaryClip();

        String version = intent.getStringExtra("api_version");
        boolean automatic = "4".equals(version);
        if (automatic || "3".equals(version)) {
            returnTypedClip(apiReceiver, context, intent, clipData, automatic);
            return;
        }

        boolean version2 = "2".equals(version);
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
                ResultReturner.returnData(apiReceiver, intent, out -> writeText(context, clipData, out, false));
            }
        } else {
            final String newClipText = intent.getStringExtra("text");
            if (newClipText != null) {
                // Set clip.
                clipboard.setPrimaryClip(ClipData.newPlainText("", newClipText));
            }

            ResultReturner.returnData(apiReceiver, intent, out -> {
                if (newClipText == null) writeText(context, clipData, out, false);
            });
        }
    }

    private static void writeText(Context context, ClipData clip, PrintWriter out, boolean textOnly) {
        if (clip == null) return;
        for (int i = 0; i < clip.getItemCount(); i++) {
            Item item = clip.getItemAt(i);
            CharSequence text = textOnly ? item.getText() : item.coerceToText(context);
            if (!TextUtils.isEmpty(text)) out.print(text);
        }
    }

    private static void returnTypedClip(TermuxApiReceiver receiver, Context context, Intent intent,
                                        ClipData clip, boolean automatic) {
        ResultReturner.returnData(receiver, intent, new ResultReturner.BinaryOutput() {
            @Override
            public void writeResult(OutputStream out) throws IOException {
                boolean textOnly = automatic && intent.getBooleanExtra("text_only", false);
                if (textOnly) {
                    writeTypedText(out, context, clip, true);
                    return;
                }

                ClipDescription description = clip == null ? null : clip.getDescription();
                PersistableBundle extras = description == null ? null : description.getExtras();
                boolean sensitive = extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE);

                ClipboardImage image;
                try {
                    image = sensitive && !automatic ? null : ClipboardImage.find(clip, context.getContentResolver());
                } catch (SecurityException e) {
                    writeImageHeader(out, "unreadable", 0);
                    return;
                }
                if (image == null && automatic) {
                    writeTypedText(out, context, clip, false);
                    return;
                }
                if (image == null || sensitive) {
                    writeImageHeader(out, "unavailable", 0);
                    return;
                }
                ByteArrayOutputStream imageBytes = new ByteArrayOutputStream(64 * 1024);
                try {
                    ClipboardImage.copy(context.getContentResolver(), image.uri, imageBytes);
                } catch (IOException | SecurityException e) {
                    writeImageHeader(out, "unreadable", 0);
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

    private static void writeTypedText(OutputStream out, Context context, ClipData clip, boolean textOnly)
        throws IOException {
        out.write("\0TPPTXT1\n".getBytes(StandardCharsets.US_ASCII));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writeText(context, clip, writer, textOnly);
        writer.flush();
    }

    private static void writeImageHeader(OutputStream out, String mimeType, int length) throws IOException {
        out.write(("\0TPPIMG1\n" + mimeType + "\n" + length + "\n").getBytes(StandardCharsets.UTF_8));
    }

}
