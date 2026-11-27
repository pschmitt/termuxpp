package dev.pschmitt.termux.shared.termux.clipboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentResolver;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** A clipboard image backed by an Android content provider. */
public final class ClipboardImage {
    public static final long MAX_BYTES = 32L * 1024 * 1024;

    public final Uri uri;
    public final String mimeType;

    private ClipboardImage(Uri uri, String mimeType) {
        this.uri = uri;
        this.mimeType = mimeType;
    }

    @Nullable
    public static ClipboardImage find(@Nullable ClipData clip, ContentResolver resolver) {
        if (clip == null || clip.getItemCount() == 0) return null;
        Uri uri = clip.getItemAt(0).getUri();
        if (uri == null) return null;

        String type = resolver.getType(uri);
        ClipDescription description = clip.getDescription();
        for (int i = 0; type == null && description != null && i < description.getMimeTypeCount(); i++) {
            String candidate = description.getMimeType(i);
            if (candidate.startsWith("image/")) type = candidate;
        }
        if (type == null || !type.startsWith("image/")) return null;
        return new ClipboardImage(uri, type);
    }

    public static void copy(ContentResolver resolver, Uri uri, OutputStream out) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open clipboard image");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) throw new IOException("The image is larger than 32 MiB");
                out.write(buffer, 0, read);
            }
        }
    }
}
