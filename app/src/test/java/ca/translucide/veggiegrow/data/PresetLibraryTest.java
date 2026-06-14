package ca.translucide.veggiegrow.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import ca.translucide.veggiegrow.data.model.WateringRatePoint;

public class PresetLibraryTest {

    @Test
    public void scheduleHasThreeScaledStages() {
        // peak 60 mL/h, 60-day crop -> stages at day 0/20/40 with 50/80/100% of peak.
        List<WateringRatePoint> s = PresetLibrary.buildSchedule(60, 60);
        assertEquals(3, s.size());
        assertEquals(0, s.get(0).dayOffset);
        assertEquals(20, s.get(1).dayOffset);
        assertEquals(40, s.get(2).dayOffset);
        assertEquals(30.0, s.get(0).rate, 1e-9); // 50%
        assertEquals(48.0, s.get(1).rate, 1e-9); // 80%
        assertEquals(60.0, s.get(2).rate, 1e-9); // 100%
    }

    @Test
    public void shortCropStagesStayOrderedAndPositive() {
        // Fast crop (radish, 25 days, low rate) must still yield increasing, >=1 stages.
        List<WateringRatePoint> s = PresetLibrary.buildSchedule(8, 25);
        assertEquals(3, s.size());
        assertTrue(s.get(0).dayOffset < s.get(1).dayOffset);
        assertTrue(s.get(1).dayOffset < s.get(2).dayOffset);
        for (WateringRatePoint p : s) {
            assertTrue(p.rate >= 1.0);
        }
    }
}
