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
    public void harvest_anchorsOnLastHarvestWhenSet() {
        // First harvest scheduled day 30, then every 20 days. Harvested early at day 20 (today).
        Bin b = binStartedDaysAgo(20);
        b.firstHarvestDays = 30;
        b.harvestIntervalDays = 20;
        b.lastHarvestEpochMillis = NOW; // harvested today (day 20)
        // Next harvest should be 20 days out (day 40), not the original day 30.
        assertEquals(20, GrowthCalculator.daysUntilHarvest(b, NOW));
    }

    @Test
    public void harvest_advancesAcrossMissedIntervalsFromLastHarvest() {
        Bin b = binStartedDaysAgo(45);
        b.firstHarvestDays = 30;
        b.harvestIntervalDays = 20;
        b.lastHarvestEpochMillis = NOW - 25 * DAY; // harvested 25 days ago, interval 20 passed
        // 25 days since last harvest -> next boundary is 40 days after last harvest = 15 days out.
        assertEquals(15, GrowthCalculator.daysUntilHarvest(b, NOW));
    }

    @Test
    public void harvest_singleHarvestDoneHasNoNext() {
        Bin b = binStartedDaysAgo(10);
        b.firstHarvestDays = 30;
        b.harvestIntervalDays = 0;       // single harvest
        b.lastHarvestEpochMillis = NOW;  // already harvested
        assertEquals(GrowthCalculator.NO_HARVEST, GrowthCalculator.daysUntilHarvest(b, NOW));
    }

    @Test
    public void reservoir_depletesFromRefillPerHour() {
        // Volumes in mL, rates in mL/h.
        GrowthSpace s = new GrowthSpace();
        s.waterReservoirSize = 20000.0;            // 20 L
        s.lastRefillEpochMillis = NOW - 10 * DAY;  // refilled 10 days (240 h) ago
        Bin b = binStartedDaysAgo(20);
        b.wateringSchedule.add(new WateringRatePoint(0, 50.0)); // 50 mL/h
        s.bins.add(b);
        // consumed = 50 mL/h * 240 h = 12000 -> remaining 8000
        assertEquals(8000.0, GrowthCalculator.estimatedReservoirRemaining(s, NOW), 1e-6);
    }

    @Test
    public void reservoir_lowTriggersAlert() {
        Settings settings = new Settings();
        settings.minWaterLevel = 9000.0; // mL
        GrowthSpace s = new GrowthSpace();
        s.waterReservoirSize = 20000.0;
        s.lastRefillEpochMillis = NOW - 10 * DAY;
        Bin b = binStartedDaysAgo(20);
        b.wateringSchedule.add(new WateringRatePoint(0, 50.0));
        s.bins.add(b);
        // remaining 8000 <= min 9000 -> low
        assertTrue(GrowthCalculator.isReservoirLow(s, settings, NOW));
    }

    @Test
    public void reservoir_fullWhenNeverRefilled() {
        GrowthSpace s = new GrowthSpace();
        s.waterReservoirSize = 20000.0;
        s.lastRefillEpochMillis = 0;
        assertEquals(20000.0, GrowthCalculator.estimatedReservoirRemaining(s, NOW), 1e-9);
    }
}
