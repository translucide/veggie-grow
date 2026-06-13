package ca.translucide.veggiegrow.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.data.model.WateringRatePoint;

public class GrowthCalculatorTest {

    private static final long DAY = GrowthCalculator.MILLIS_PER_DAY;
    // Fixed reference "now" so tests are deterministic.
    private static final long NOW = 1_700_000_000_000L;

    private Bin binStartedDaysAgo(int days) {
        Bin b = new Bin();
        b.startDateEpochMillis = NOW - days * DAY;
        return b;
    }

    @Test
    public void currentRate_picksActivePointAtBoundaries() {
        Bin b = binStartedDaysAgo(10);
        b.wateringSchedule.add(new WateringRatePoint(0, 0.05));
        b.wateringSchedule.add(new WateringRatePoint(7, 0.09));
        b.wateringSchedule.add(new WateringRatePoint(14, 0.12));
        // 10 days in -> the day-7 point is active (day-14 not reached yet).
        assertEquals(0.09, GrowthCalculator.currentWateringRate(b, NOW), 1e-9);
    }

    @Test
    public void currentRate_zeroBeforeFirstPoint() {
        Bin b = binStartedDaysAgo(2);
        b.wateringSchedule.add(new WateringRatePoint(5, 0.10));
        assertEquals(0.0, GrowthCalculator.currentWateringRate(b, NOW), 1e-9);
    }

    @Test
    public void currentRate_emptyScheduleIsZero() {
        Bin b = binStartedDaysAgo(5);
        assertEquals(0.0, GrowthCalculator.currentWateringRate(b, NOW), 1e-9);
    }

    @Test
    public void nextHarvest_firstHarvestInFuture() {
        Bin b = binStartedDaysAgo(10);
        b.firstHarvestDays = 30;       // harvest at start+30 => 20 days from now
        b.harvestIntervalDays = 0;
        assertEquals(20, GrowthCalculator.daysUntilHarvest(b, NOW));
    }

    @Test
    public void nextHarvest_recurringAdvancesPastNow() {
        Bin b = binStartedDaysAgo(40);
        b.firstHarvestDays = 30;       // first harvest was 10 days ago
        b.harvestIntervalDays = 7;     // next multiple of 7 after now
        long days = GrowthCalculator.daysUntilHarvest(b, NOW);
        assertTrue("expected upcoming harvest within a week", days > 0 && days <= 7);
    }

    @Test
    public void nextHarvest_disabledWhenPastAndNoInterval() {
        Bin b = binStartedDaysAgo(40);
        b.firstHarvestDays = 30;       // passed
        b.harvestIntervalDays = 0;     // no recurrence
        assertEquals(GrowthCalculator.NO_HARVEST, GrowthCalculator.daysUntilHarvest(b, NOW));
    }

    @Test
    public void harvestDue_respectsAlertWindow() {
        Bin b = binStartedDaysAgo(28);
        b.firstHarvestDays = 30;       // 2 days from now
        assertTrue(GrowthCalculator.isHarvestDue(b, NOW, 3));
        assertFalse(GrowthCalculator.isHarvestDue(b, NOW, 1));
    }

    @Test
    public void reservoir_depletesFromRefill() {
        GrowthSpace s = new GrowthSpace();
        s.waterReservoirSize = 20.0;
        s.lastRefillEpochMillis = NOW - 10 * DAY; // refilled 10 days ago
        Bin b = binStartedDaysAgo(20);
        b.wateringSchedule.add(new WateringRatePoint(0, 0.5)); // 0.5/day
        s.bins.add(b);
        // consumed = 0.5 * 10 = 5 -> remaining 15
        assertEquals(15.0, GrowthCalculator.estimatedReservoirRemaining(s, NOW), 1e-6);
    }

    @Test
    public void reservoir_lowTriggersAlert() {
        Settings settings = new Settings();
        settings.minWaterLevel = 16.0;
        GrowthSpace s = new GrowthSpace();
        s.waterReservoirSize = 20.0;
        s.lastRefillEpochMillis = NOW - 10 * DAY;
        Bin b = binStartedDaysAgo(20);
        b.wateringSchedule.add(new WateringRatePoint(0, 0.5));
        s.bins.add(b);
        // remaining 15 <= min 16 -> low
        assertTrue(GrowthCalculator.isReservoirLow(s, settings, NOW));
    }

    @Test
    public void reservoir_fullWhenNeverRefilled() {
        GrowthSpace s = new GrowthSpace();
        s.waterReservoirSize = 20.0;
        s.lastRefillEpochMillis = 0;
        assertEquals(20.0, GrowthCalculator.estimatedReservoirRemaining(s, NOW), 1e-9);
    }
}
