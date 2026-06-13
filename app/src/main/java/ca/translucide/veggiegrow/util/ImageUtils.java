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
 * Images are downscaled and JPEG-compressed to keep the JSON a reasonable size.
 */
public final class ImageUtils {

    private static final int MAX_DIMENSION = 1024;
    private static final int JPEG_QUALITY = 80;

    private ImageUtils() {
    }

    private static final String USER_AGENT = "VeggieGrow/1.0 (Android)";

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

    /** Downloads an image URL and decodes it, downscaled to {@link #MAX_DIMENSION}. Blocking. */
    public static Bitmap downloadBitmap(String urlString) throws IOException {
        byte[] bytes = download(urlString);
        // First pass: bounds only.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / sample > MAX_DIMENSION) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
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

    private static Bitmap decodeScaled(Context context, Uri uri) throws Exception {
        // First pass: bounds only, to compute a sample size.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / sample > MAX_DIMENSION) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }
}
