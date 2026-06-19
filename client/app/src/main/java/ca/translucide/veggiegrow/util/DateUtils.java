package ca.translucide.veggiegrow.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Small date helpers shared by the UI. Dates are stored as epoch millis at local midnight.
 */
public final class DateUtils {

    private static final SimpleDateFormat DISPLAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private DateUtils() {
    }

    /** Normalises a timestamp to local midnight of that day. */
    public static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long todayMillis() {
        return startOfDay(System.currentTimeMillis());
    }

    public static String format(long millis) {
        if (millis <= 0) return "—";
        return DISPLAY.format(new Date(millis));
    }
}
