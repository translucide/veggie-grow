package ca.translucide.veggiegrow.logic;

import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.data.model.WateringRatePoint;

/**
 * Pure (Android-free) growth/water computations. Kept free of framework dependencies so it can be
 * unit-tested on the JVM.
 *
 * <p>All "now" values are epoch millis passed in by the caller, keeping the class deterministic.
 */
public final class GrowthCalculator {

    public static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    /** Sentinel returned by {@link #daysUntilHarvest} when recurring harvests are disabled and past. */
    public static final long NO_HARVEST = Long.MIN_VALUE;

    private GrowthCalculator() {
    }

    /** Whole days elapsed since the bin start date (can be negative if start is in the future). */
    public static int daysSinceStart(Bin bin, long nowMillis) {
        return (int) Math.floor((nowMillis - bin.startDateEpochMillis) / (double) MILLIS_PER_DAY);
    }

    /**
     * The currently-active watering rate: the schedule point with the greatest dayOffset that is
     * still &lt;= the elapsed days. Returns 0 if no point applies yet (or the schedule is empty).
     */
    public static double currentWateringRate(Bin bin, long nowMillis) {
        if (bin.wateringSchedule == null || bin.wateringSchedule.isEmpty()) {
            return 0d;
        }
        int days = daysSinceStart(bin, nowMillis);
        double rate = 0d;
        int bestOffset = Integer.MIN_VALUE;
        boolean found = false;
        for (WateringRatePoint p : bin.wateringSchedule) {
            if (p.dayOffset <= days && p.dayOffset >= bestOffset) {
                bestOffset = p.dayOffset;
                rate = p.rate;
                found = true;
            }
        }
        return found ? rate : 0d;
    }

    /**
     * Next harvest date as epoch millis, or {@link Long#MIN_VALUE} if there is no upcoming harvest
     * (recurring disabled and the single harvest has already happened/passed).
     *
     * <p>If a harvest has been recorded ({@link Bin#lastHarvestEpochMillis} &gt; 0), upcoming
     * harvests are anchored on that date + interval. Otherwise they are anchored on the start date
     * + {@link Bin#firstHarvestDays}.
     */
    public static long nextHarvestDate(Bin bin, long nowMillis) {
        long interval = bin.harvestIntervalDays * MILLIS_PER_DAY;

        if (bin.lastHarvestEpochMillis > 0) {
            // Re-anchored on the actual last harvest.
            if (bin.harvestIntervalDays <= 0) {
                return NO_HARVEST; // single harvest, already done
            }
            long elapsed = nowMillis - bin.lastHarvestEpochMillis;
            long periods = (long) Math.ceil(elapsed / (double) interval);
            if (periods < 1) periods = 1; // always at least one interval after a harvest
            return bin.lastHarvestEpochMillis + periods * interval;
        }

        long first = bin.startDateEpochMillis + bin.firstHarvestDays * MILLIS_PER_DAY;
        if (first >= nowMillis) {
            return first;
        }
        // First harvest already passed.
        if (bin.harvestIntervalDays <= 0) {
            return NO_HARVEST;
        }
        long elapsed = nowMillis - first;
        long periods = (elapsed + interval - 1) / interval; // ceil division -> next boundary
        return first + periods * interval;
    }

    /**
     * Whole days until the next harvest (rounded up), or {@link #NO_HARVEST} if none upcoming.
     * 0 means "due today or overdue boundary".
     */
    public static long daysUntilHarvest(Bin bin, long nowMillis) {
        long next = nextHarvestDate(bin, nowMillis);
        if (next == NO_HARVEST) return NO_HARVEST;
        return (long) Math.ceil((next - nowMillis) / (double) MILLIS_PER_DAY);
    }

    /** True when the bin's next harvest is within {@code alertDays} days. */
    public static boolean isHarvestDue(Bin bin, long nowMillis, int alertDays) {
        long d = daysUntilHarvest(bin, nowMillis);
        return d != NO_HARVEST && d <= alertDays;
    }

    // --- Reservoir (manual refill tracking) ----------------------------------------------------

    /**
     * Sum of all bins' currently-active watering rates in a space. Used as the daily consumption
     * estimate for the reservoir.
     */
    public static double totalCurrentWateringRate(GrowthSpace space, long nowMillis) {
        double sum = 0d;
        for (Bin b : space.bins) {
            sum += currentWateringRate(b, nowMillis);
        }
        return sum;
    }

    /**
     * Estimated reservoir volume remaining since the last manual refill:
     * {@code reservoirSize - (sum of current watering rates) * daysSinceRefill}.
     *
     * <p>This is the single place the depletion model lives; tweak it here to change behaviour.
     * If the space has never been refilled, we assume it is full.
     */
    public static double estimatedReservoirRemaining(GrowthSpace space, long nowMillis) {
        if (space.lastRefillEpochMillis <= 0) {
            return space.waterReservoirSize;
        }
        double daysSinceRefill = (nowMillis - space.lastRefillEpochMillis) / (double) MILLIS_PER_DAY;
        if (daysSinceRefill < 0) daysSinceRefill = 0;
        double consumed = totalCurrentWateringRate(space, nowMillis) * daysSinceRefill;
        double remaining = space.waterReservoirSize - consumed;
        return Math.max(0d, remaining);
    }

    /** True when the estimated remaining volume is at or below the configured minimum. */
    public static boolean isReservoirLow(GrowthSpace space, Settings settings, long nowMillis) {
        return estimatedReservoirRemaining(space, nowMillis) <= settings.minWaterLevel;
    }
}
