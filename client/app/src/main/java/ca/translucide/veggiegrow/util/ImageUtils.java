package ca.translucide.veggiegrow.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Converts user-picked images to/from base64 so they travel inside the JSON model and exports.
 * Images are downscaled to {@link #MAX_DIMENSION}px and JPEG-compressed to keep the JSON small —
 * the whole model is synced as one file, so large images make every pull/push slow.
 */
public final class ImageUtils {

    /** Max width/height (px) of any stored image. Thumbnails on screen, so 256 is plenty. */
    private static final int MAX_DIMENSION = 256;
    private static final int JPEG_QUALITY = 80;

    /**
     * Base64 length above which an image is considered "oversized" and worth recompressing. A
     * 256px JPEG at quality 80 is comfortably under this, so well-sized images are never re-touched.
     */
    private static final int OVERSIZED_BASE64_CHARS = 90_000;

    private static final String USER_AGENT = "VeggieGrow/1.0 (Android)";

    private ImageUtils() {
    }

    /** Loads, downscales and encodes a picked image uri as a base64 JPEG string. */
    public static String uriToBase64(Context context, Uri uri) throws Exception {
        Bitmap bitmap = decodeScaled(context, uri);
        if (bitmap == null) throw new IllegalArgumentException("Could not decode image");
        return bitmapToBase64(bitmap);
    }

    /** Downloads a remote image, downscales it and encodes it as a base64 JPEG string. */
    public static String urlToBase64(String urlString) throws Exception {
        Bitmap bitmap = downloadBitmap(urlString);
        if (bitmap == null) throw new IllegalArgumentException("Could not decode image from " + urlString);
        return bitmapToBase64(bitmap);
    }

    /** Compresses and base64-encodes a bitmap, then recycles it. */
    public static String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        bitmap.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /**
     * Re-compresses an already-stored base64 image if it is oversized (legacy images saved before the
     * 256px limit). Returns the smaller base64, or null if no change is needed or decoding failed.
     * Heavy (decode + scale + encode) — call off the main thread.
     */
    public static String recompressIfOversized(String base64) {
        if (base64 == null || base64.length() <= OVERSIZED_BASE64_CHARS) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            Bitmap bitmap = decodeScaledBytes(bytes);
            if (bitmap == null) return null;
            return bitmapToBase64(bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    /** Downloads an image URL and decodes it, downscaled to {@link #MAX_DIMENSION}. Blocking. */
    public static Bitmap downloadBitmap(String urlString) throws IOException {
        return decodeScaledBytes(download(urlString));
    }

    /** Decodes a base64 string back to a Bitmap, or null if empty/invalid. */
    public static Bitmap base64ToBitmap(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    // --- decoding helpers ------------------------------------------------------------------------

    private static Bitmap decodeScaled(Context context, Uri uri) throws Exception {
        // First pass: bounds only, to compute a coarse sample size.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds);
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        return scaleToMax(decoded);
    }

    private static Bitmap decodeScaledBytes(byte[] bytes) {
        if (bytes == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds);
        return scaleToMax(BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts));
    }

    /**
     * Largest power-of-two sample size that still leaves the image at least {@link #MAX_DIMENSION}px
     * on its longer side, so {@link #scaleToMax} can then trim it down precisely without quality loss.
     */
    private static int sampleSizeFor(BitmapFactory.Options bounds) {
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (largest / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2;
        }
        return sample;
    }

    /** Scales a bitmap so its longer side is exactly {@link #MAX_DIMENSION}, preserving aspect ratio. */
    private static Bitmap scaleToMax(Bitmap src) {
        if (src == null) return null;
        int largest = Math.max(src.getWidth(), src.getHeight());
        if (largest <= MAX_DIMENSION) return src;
        float ratio = (float) MAX_DIMENSION / largest;
        int w = Math.max(1, Math.round(src.getWidth() * ratio));
        int h = Math.max(1, Math.round(src.getHeight() * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    private static byte[] download(String urlString) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + urlString);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
