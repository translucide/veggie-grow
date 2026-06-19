package ca.translucide.veggiegrow.logic;

import java.util.Locale;

import ca.translucide.veggiegrow.data.model.Settings.PumpRateUnit;

/**
 * Unit conversions and formatting between the canonical internal storage units (flow rates in
 * mL/h, volumes in mL) and the user's chosen display unit (LPH = litres, GPH = gallons).
 *
 * <p>The unit preference is display-only: nothing here is persisted. Pure / Android-free.
 */
public final class Units {

    public static final double ML_PER_LITRE = 1000.0;
    public static final double ML_PER_GALLON = 3785.411784; // US gallon

    private Units() {
    }

    private static double factor(PumpRateUnit unit) {
        return unit == PumpRateUnit.GPH ? ML_PER_GALLON : ML_PER_LITRE;
    }

    // --- Flow rates (canonical mL/h) -----------------------------------------------------------

    /** Canonical mL/h -> display value in the chosen unit (L/h or gal/h). */
    public static double rateToDisplay(double mlPerHour, PumpRateUnit unit) {
        return mlPerHour / factor(unit);
    }

    /** Display value (L/h or gal/h) -> canonical mL/h. */
    public static double rateFromDisplay(double displayValue, PumpRateUnit unit) {
        return displayValue * factor(unit);
    }

    public static String rateUnitLabel(PumpRateUnit unit) {
        return unit == PumpRateUnit.GPH ? "GPH" : "LPH";
    }

    public static String formatRate(double mlPerHour, PumpRateUnit unit) {
        return num(rateToDisplay(mlPerHour, unit)) + " " + rateUnitLabel(unit);
    }

    // --- Volumes (canonical mL) ----------------------------------------------------------------

    /** Canonical mL -> display value in the chosen unit (litres or gallons). */
    public static double volumeToDisplay(double ml, PumpRateUnit unit) {
        return ml / factor(unit);
    }

    /** Display value (litres or gallons) -> canonical mL. */
    public static double volumeFromDisplay(double displayValue, PumpRateUnit unit) {
        return displayValue * factor(unit);
    }

    public static String volumeUnitLabel(PumpRateUnit unit) {
        return unit == PumpRateUnit.GPH ? "gal" : "L";
    }

    public static String formatVolume(double ml, PumpRateUnit unit) {
        return num(volumeToDisplay(ml, unit)) + " " + volumeUnitLabel(unit);
    }

    /** Formats a number with up to two decimals, trimming trailing zeros (e.g. 20, 0.09, 1.5). */
    public static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.format(Locale.US, "%d", (long) v);
        }
        String s = String.format(Locale.US, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}
