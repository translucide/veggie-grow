package ca.translucide.veggiegrow.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ca.translucide.veggiegrow.data.model.Settings.PumpRateUnit;

public class UnitsTest {

    @Test
    public void litreConversionsRoundTrip() {
        // 1500 mL/h = 1.5 LPH
        assertEquals(1.5, Units.rateToDisplay(1500, PumpRateUnit.LPH), 1e-9);
        assertEquals(1500, Units.rateFromDisplay(1.5, PumpRateUnit.LPH), 1e-9);
        // 20000 mL = 20 L
        assertEquals(20, Units.volumeToDisplay(20000, PumpRateUnit.LPH), 1e-9);
        assertEquals(20000, Units.volumeFromDisplay(20, PumpRateUnit.LPH), 1e-9);
    }

    @Test
    public void gallonConversions() {
        // 1 US gallon = 3785.411784 mL
        assertEquals(1.0, Units.volumeToDisplay(3785.411784, PumpRateUnit.GPH), 1e-9);
        assertEquals(3785.411784, Units.rateFromDisplay(1.0, PumpRateUnit.GPH), 1e-9);
    }

    @Test
    public void labels() {
        assertEquals("LPH", Units.rateUnitLabel(PumpRateUnit.LPH));
        assertEquals("GPH", Units.rateUnitLabel(PumpRateUnit.GPH));
        assertEquals("L", Units.volumeUnitLabel(PumpRateUnit.LPH));
        assertEquals("gal", Units.volumeUnitLabel(PumpRateUnit.GPH));
    }

    @Test
    public void numTrimsTrailingZeros() {
        assertEquals("20", Units.num(20.0));
        assertEquals("0.09", Units.num(0.09));
        assertEquals("1.5", Units.num(1.5));
    }

    @Test
    public void formatHelpers() {
        assertEquals("1.5 LPH", Units.formatRate(1500, PumpRateUnit.LPH));
        assertEquals("20 L", Units.formatVolume(20000, PumpRateUnit.LPH));
    }
}
