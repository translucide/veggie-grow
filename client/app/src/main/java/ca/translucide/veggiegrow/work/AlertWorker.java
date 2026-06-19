package ca.translucide.veggiegrow.work;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.VeggieGrowApp;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.logic.Alert;
import ca.translucide.veggiegrow.logic.AlertEngine;
import ca.translucide.veggiegrow.ui.MainActivity;
import ca.translucide.veggiegrow.util.DateUtils;

/**
 * Periodic background job that recomputes alerts and posts a system notification per active alert.
 */
public class AlertWorker extends Worker {

    public AlertWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        DataRepository.init(context);
        List<Alert> alerts = AlertEngine.evaluate(DataRepository.get().data(), DateUtils.todayMillis());

        if (alerts.isEmpty() || !canNotify(context)) {
            return Result.success();
        }

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        int id = 1000;
        for (Alert a : alerts) {
            Notification n = new NotificationCompat.Builder(context, VeggieGrowApp.ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_leaf)
                    .setContentTitle(a.title)
                    .setContentText(a.message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(a.message))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id++, n);
        }
        return Result.success();
    }

    private boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}
