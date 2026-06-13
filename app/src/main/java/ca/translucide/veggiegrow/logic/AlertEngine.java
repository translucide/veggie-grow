package ca.translucide.veggiegrow.logic;

import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.data.model.AppData;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;

/**
 * Derives the current list of {@link Alert}s from the app model. Pure / Android-free.
 */
public final class AlertEngine {

    private AlertEngine() {
    }

    public static List<Alert> evaluate(AppData data, long nowMillis) {
        List<Alert> alerts = new ArrayList<>();
        Settings settings = data.settings;

        for (GrowthSpace space : data.spaces) {
            // Harvest alerts (one per due bin).
            for (Bin bin : space.bins) {
                if (GrowthCalculator.isHarvestDue(bin, nowMillis, settings.harvestAlertDays)) {
                    String ref = binReference(space, bin);
                    long days = GrowthCalculator.daysUntilHarvest(bin, nowMillis);
                    String when = days <= 0 ? "now" : "in " + days + (days == 1 ? " day" : " days");
                    alerts.add(new Alert(
                            Alert.Type.HARVEST,
                            "Harvest due: " + ref,
                            "Bin " + ref + " (" + safe(bin.varietyName) + ") harvest due " + when + ".",
                            ref));
                }
            }

            // Reservoir refill alert (one per low space).
            if (space.waterReservoirSize > 0
                    && GrowthCalculator.isReservoirLow(space, settings, nowMillis)) {
                double remaining = GrowthCalculator.estimatedReservoirRemaining(space, nowMillis);
                alerts.add(new Alert(
                        Alert.Type.RESERVOIR,
                        "Refill reservoir: " + space.code,
                        "Space " + space.code + " (" + safe(space.name) + ") reservoir is low "
                                + "(~" + round1(remaining) + " of " + round1(space.waterReservoirSize) + ").",
                        space.code));
            }
        }
        return alerts;
    }

    public static String binReference(GrowthSpace space, Bin bin) {
        return safe(space.code) + safe(bin.code);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
