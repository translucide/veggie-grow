package ca.translucide.veggiegrow.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ca.translucide.veggiegrow.data.model.AppData;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.WateringRatePoint;

public class CommandBuilderTest {

    private static final long DAY = GrowthCalculator.MILLIS_PER_DAY;
    private static final long NOW = 1_700_000_000_000L;

    private Bin bin(String code, double rate) {
        Bin b = new Bin();
        b.code = code;
        b.startDateEpochMillis = NOW - 5 * DAY;
        if (rate > 0) b.wateringSchedule.add(new WateringRatePoint(0, rate));
        return b;
    }

    @Test
    public void buildsExpectedPayloadFormat() {
        AppData data = new AppData();
        GrowthSpace a = new GrowthSpace();
        a.code = "A";
        a.bins.add(bin("1", 0.09));
        a.bins.add(bin("2", 0.08));
        data.spaces.add(a);

        assertEquals("A1:0.09,A2:0.08", CommandBuilder.buildPayload(data, NOW));
    }

    @Test
    public void omitsZeroRateBins() {
        AppData data = new AppData();
        GrowthSpace a = new GrowthSpace();
        a.code = "A";
        a.bins.add(bin("1", 0.10));
        a.bins.add(bin("2", 0.0)); // no rate -> omitted
        data.spaces.add(a);

        assertEquals("A1:0.1", CommandBuilder.buildPayload(data, NOW));
    }

    @Test
    public void formatRate_trimsTrailingZeros() {
        assertEquals("0.09", CommandBuilder.formatRate(0.09));
        assertEquals("1", CommandBuilder.formatRate(1.0));
        assertEquals("90", CommandBuilder.formatRate(90.0));
    }

    @Test
    public void emptyWhenNoBins() {
        AppData data = new AppData();
        assertEquals("", CommandBuilder.buildPayload(data, NOW));
    }
}
