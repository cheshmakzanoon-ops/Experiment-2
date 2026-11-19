package com.artflow.qualification;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Receives only the app's actual chooser grant; this component is never packaged with ArtFlow. */
public final class PngReceiptActivity extends Activity {
    private static final int MAX_PNG_BYTES = 512 * 1024;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences pending = getSharedPreferences("receipt", MODE_PRIVATE);
        String token = pending.getString("token", "");
        String phase = pending.getString("phase", "");
        if (!token.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !phase.matches("saved|reopened")) {
            finish();
            return;
        }
        File output = new File(getExternalFilesDir(null), "release-ui/" + token);
        try {
            Intent incoming = getIntent();
            require(Intent.ACTION_SEND.equals(incoming.getAction()), "Expected a real ACTION_SEND");
            require("image/png".equals(incoming.getType()), "Expected a PNG share");
            Uri uri = incoming.getParcelableExtra(Intent.EXTRA_STREAM);
            require(uri != null && "content".equals(uri.getScheme())
                    && "com.artflow.studio.fileprovider".equals(uri.getAuthority()), "Unexpected content provider");
            require((incoming.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0, "Missing read grant");
            require(incoming.getClipData() != null && incoming.getClipData().getItemCount() == 1
                    && uri.equals(incoming.getClipData().getItemAt(0).getUri()), "Share ClipData differs");
            String expectedName = "release-" + phase + ".png";
            require(expectedName.equals(incoming.getStringExtra(Intent.EXTRA_SUBJECT)), "Unexpected shared artwork");
            byte[] bytes;
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                require(input != null, "The read grant could not open its PNG");
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    require(buffer.size() + read <= MAX_PNG_BYTES, "Oversized qualification PNG");
                    buffer.write(chunk, 0, read);
                }
                bytes = buffer.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            require(bounds.outWidth == 64 && bounds.outHeight == 64, "Unexpected exported dimensions");
            Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            require(image != null, "The shared image is not readable");
            JSONObject report;
            try {
                require(image.getWidth() == 64 && image.getHeight() == 64, "Unexpected exported dimensions");
                int[] pixels = new int[64 * 64];
                image.getPixels(pixels, 0, 64, 0, 0, 64, 64);
                ByteBuffer raw = ByteBuffer.allocate(pixels.length * 4);
                int ink = 0;
                for (int pixel : pixels) {
                    require((pixel >>> 24) == 255, "Flatten-white PNG must be opaque");
                    if ((pixel & 0xFFFFFF) != 0xFFFFFF) ink++;
                    raw.putInt(pixel);
                }
                require(ink >= 8 && ink < pixels.length, "Pointer painting must create nonblank, partly white artwork");
                report = new JSONObject().put("schemaVersion", 1).put("token", token).put("phase", phase)
                        .put("uri", uri.toString()).put("readGrant", true).put("width", 64).put("height", 64)
                        .put("inkPixels", ink).put("pngSha256", digest(bytes)).put("pixelSha256", digest(raw.array()));
                Files.write(new File(output, phase + "-pixels.argb").toPath(), raw.array(),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            } finally {
                image.recycle();
            }
            Files.write(new File(output, phase + ".png").toPath(), bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.write(new File(output, phase + "-receipt.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            try {
                Files.write(new File(output, phase + "-error.txt").toPath(), failure.toString().getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (Exception secondary) {
                failure.addSuppressed(secondary);
            }
        } finally {
            finish();
        }
    }

    static String digest(byte[] bytes) throws Exception {
        byte[] value = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte item : value) text.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return text.toString();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
