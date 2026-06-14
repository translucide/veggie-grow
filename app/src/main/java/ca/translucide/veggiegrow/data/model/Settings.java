package ca.translucide.veggiegrow.data.model;

/**
 * Global application settings (a single instance lives on {@link AppData}).
 *
 * <p><b>Units:</b> all stored quantities are canonical and unit-agnostic of the user's preference:
 * flow rates are in <b>millilitres per hour (mL/h)</b> and volumes are in <b>millilitres (mL)</b>.
 * {@link #pumpRateUnit} is purely a display preference applied when showing/editing values.
 */
public class Settings {

    public enum PumpRateUnit {
        LPH, // display rates in litres/hour, volumes in litres
        GPH  // display rates in gallons/hour, volumes in gallons
    }

    /** Pump flow rate in mL/h (canonical). Informational. */
    public double pumpRate = 1000.0;

    public PumpRateUnit pumpRateUnit = PumpRateUnit.LPH;

    /** Reservoir volume in mL at/below which we alert. */
    public double minWaterLevel = 1000.0;

    /** Raise a harvest alert when a bin is due within this many days. */
    public int harvestAlertDays = 3;

    /** When true the app talks to {@code MockHardwareGateway} instead of real Bluetooth. */
    public boolean useMockHardware = true;
}
