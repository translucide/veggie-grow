package ca.translucide.veggiegrow;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ca.translucide.veggiegrow.data.CloudSync;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.imagesearch.ImageResult;
import ca.translucide.veggiegrow.imagesearch.ImageSearchClient;
import ca.translucide.veggiegrow.util.ImageUtils;
import ca.translucide.veggiegrow.work.AlertWorker;

/**
 * Application entry point: initialises the data repository, the notification channel, and schedules
 * the periodic alert check.
 */
public class VeggieGrowApp extends Application {

    public static final String ALERT_CHANNEL_ID = "veggiegrow_alerts";
    private static final String ALERT_WORK_NAME = "veggiegrow_alert_check";

    private CloudSync cloudSync;

    /** Process-wide sync manager; enabled by the auth flow once the user belongs to an account. */
    public CloudSync cloudSync() {
        return cloudSync;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DataRepository repo = DataRepository.init(this);
        cloudSync = new CloudSync(this, repo);
        repo.setSyncListener(cloudSync);
        cloudSync.attachLifecycle();
        createNotificationChannel();
        scheduleAlertChecks();
        compactOversizedImages();
        if (repo.wasSeededThisLaunch()) {
            fetchDefaultPhotos();
        }
    }

    /**
     * One-time shrink of any legacy oversized images (saved before the 256px limit), so the synced
     * file stops weighing several MB. Runs off the main thread; persists + notifies on the main thread
     * only if something actually changed.
     */
    private void compactOversizedImages() {
        new Thread(() -> {
            DataRepository repo = DataRepository.get();
            if (repo.compactOversizedImages()) {
                new Handler(Looper.getMainLooper()).post(repo::commit);
            }
        }, "img-compact").start();
    }

    /**
     * Best-effort, one-time background fetch of a photo for each seeded default bin (and its
     * matching preset), using the same image search the bin form offers. Requires network; any
     * failure is silently skipped so the leaf placeholder simply remains.
     */
    private void fetchDefaultPhotos() {
        new Thread(() -> {
            DataRepository repo = DataRepository.get();
            GrowthSpace space = repo.data().findSpace("A");
            if (space == null) return;
            ImageSearchClient client = new ImageSearchClient();
            Handler main = new Handler(Looper.getMainLooper());
            for (Bin bin : space.bins) {
                final String variety = bin.varietyName;
                if (variety == null || variety.isEmpty()) continue;
                try {
                    List<ImageResult> results = client.search(variety);
                    if (results.isEmpty()) continue;
                    final String base64 = ImageUtils.urlToBase64(results.get(0).fullUrl);
                    main.post(() -> applyPhoto(variety, base64));
                } catch (Exception ignored) {
                    // network/decoding failure -> keep placeholder
                }
            }
        }, "seed-photos").start();
    }

    private void applyPhoto(String variety, String base64) {
        DataRepository repo = DataRepository.get();
        GrowthSpace space = repo.data().findSpace("A");
        if (space != null) {
            for (Bin bin : space.bins) {
                if (variety.equals(bin.varietyName) && bin.imageBase64 == null) {
                    bin.imageBase64 = base64;
                    repo.updateBin(space, bin);
                }
            }
        }
        for (Preset p : repo.data().presets) {
            if (variety.equals(p.name) && p.imageBase64 == null) {
                p.imageBase64 = base64;
                repo.upsertPreset(p);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.alert_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void scheduleAlertChecks() {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AlertWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(Constraints.NONE)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                ALERT_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }
}
