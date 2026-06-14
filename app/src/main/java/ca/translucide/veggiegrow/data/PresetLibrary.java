package ca.translucide.veggiegrow.data;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.data.model.WateringRatePoint;

/**
 * Loads the bundled vegetable-variety preset library ({@code assets/preset_library.json}) and
 * expands each compact entry into a full {@link Preset} with a three-stage watering schedule.
 *
 * <p>Watering values are canonical mL/h (see {@code Settings}). The library is intentionally
 * compact (one peak rate per variety); the per-stage schedule is derived here.
 */
public final class PresetLibrary {

    private static final String ASSET = "preset_library.json";

    /** Compact on-disk entry. */
    private static class Entry {
        String name;
        double peakRate;     // mL/h at maturity
        int firstHarvest;    // days from start
        int interval;        // repeat-harvest days (0 = single harvest)
    }

    private PresetLibrary() {
    }

    @NonNull
    public static List<Preset> load(@NonNull Context context) {
        List<Preset> presets = new ArrayList<>();
        try (InputStream in = context.getAssets().open(ASSET);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Gson gson = new Gson();
            List<Entry> entries = gson.fromJson(reader, new TypeToken<List<Entry>>() {
            }.getType());
            if (entries == null) return presets;
            for (Entry e : entries) {
                if (e == null || e.name == null) continue;
                Preset p = new Preset();
                p.name = e.name;
                p.varietyName = e.name;
                p.firstHarvestDays = e.firstHarvest;
                p.harvestIntervalDays = e.interval;
                p.wateringSchedule = buildSchedule(e.peakRate, e.firstHarvest);
                presets.add(p);
            }
        } catch (Exception ignored) {
            // Missing/corrupt asset: return whatever we have (possibly empty).
        }
        return presets;
    }

    /**
     * Three-stage watering schedule scaled to the crop's growth length: establishment (50% of
     * peak), vegetative (80%), and maturity (100%). Pure / testable.
     */
    @NonNull
    public static List<WateringRatePoint> buildSchedule(double peakRate, int firstHarvest) {
        int d2 = Math.max(7, firstHarvest / 3);
        int d3 = Math.max(d2 + 7, (firstHarvest * 2) / 3);
        List<WateringRatePoint> schedule = new ArrayList<>();
        schedule.add(new WateringRatePoint(0, atLeastOne(peakRate * 0.5)));
        schedule.add(new WateringRatePoint(d2, atLeastOne(peakRate * 0.8)));
        schedule.add(new WateringRatePoint(d3, atLeastOne(peakRate)));
        return schedule;
    }

    private static double atLeastOne(double v) {
        long r = Math.round(v);
        return Math.max(1, r);
    }
}
