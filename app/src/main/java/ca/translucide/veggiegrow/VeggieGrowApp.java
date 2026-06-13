package ca.translucide.veggiegrow;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.work.AlertWorker;

/**
 * Application entry point: initialises the data repository, the notification channel, and schedules
 * the periodic alert check.
 */
public class VeggieGrowApp extends Application {

    public static final String ALERT_CHANNEL_ID = "veggiegrow_alerts";
    private static final String ALERT_WORK_NAME = "veggiegrow_alert_check";

    @Override
    public void onCreate() {
        super.onCreate();
        DataRepository.init(this);
        createNotificationChannel();
        scheduleAlertChecks();
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
