package ca.translucide.veggiegrow.util;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal asynchronous image loader for the photo-picker grid (no third-party deps). Downloads
 * thumbnails on a background pool, caches decoded bitmaps in memory, and guards against recycled
 * views by tagging each {@link ImageView} with the URL it is currently loading.
 */
public class ThumbnailLoader {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    public ThumbnailLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(maxKb / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void load(String url, ImageView target, int placeholderRes) {
        if (url == null) {
            target.setImageResource(placeholderRes);
            return;
        }
        target.setTag(url);

        Bitmap cached = cache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(placeholderRes);

        executor.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = ImageUtils.downloadBitmap(url);
            } catch (Exception ignored) {
            }
            if (bmp == null) return;
            cache.put(url, bmp);
            final Bitmap result = bmp;
            main.post(() -> {
                // Only apply if this view is still meant to show this URL.
                if (url.equals(target.getTag())) {
                    target.setImageBitmap(result);
                }
            });
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
