package ca.translucide.veggiegrow.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable template for a {@link Bin}. Applying a preset prefills the bin form; the user may
 * edit the values and optionally save back / overwrite the preset.
 *
 * <p>It carries the "constant" fields (variety, image) and the "date-related" fields (watering
 * schedule, harvest cadence). It deliberately does NOT carry per-instance values such as the bin
 * code or start date.
 */
public class Preset {

    public String name = "";

    // --- Constant fields ---
    public String varietyName = "";
    public String imageBase64;

    // --- Date-related fields ---
    public List<WateringRatePoint> wateringSchedule = new ArrayList<>();
    public int firstHarvestDays;
    public int harvestIntervalDays;

    public Preset() {
    }

    /** Builds a preset snapshot from an existing bin (used for "save as preset"). */
    public static Preset fromBin(String presetName, Bin bin) {
        Preset p = new Preset();
        p.name = presetName;
        p.varietyName = bin.varietyName;
        p.imageBase64 = bin.imageBase64;
        p.firstHarvestDays = bin.firstHarvestDays;
        p.harvestIntervalDays = bin.harvestIntervalDays;
        p.wateringSchedule = new ArrayList<>();
        for (WateringRatePoint pt : bin.wateringSchedule) {
            p.wateringSchedule.add(new WateringRatePoint(pt.dayOffset, pt.rate));
        }
        return p;
    }

    /** Prefills the given bin form with this preset's values (leaves code/start date untouched). */
    public void applyTo(Bin bin) {
        bin.varietyName = varietyName;
        bin.imageBase64 = imageBase64;
        bin.firstHarvestDays = firstHarvestDays;
        bin.harvestIntervalDays = harvestIntervalDays;
        bin.wateringSchedule = new ArrayList<>();
        for (WateringRatePoint pt : wateringSchedule) {
            bin.wateringSchedule.add(new WateringRatePoint(pt.dayOffset, pt.rate));
        }
    }
}
