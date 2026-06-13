package ca.translucide.veggiegrow.data.model;

/**
 * Global application settings (a single instance lives on {@link AppData}).
 */
public class Settings {

    public enum PumpRateUnit {
        LPM, // litres per minute
        GPM  // gallons per minute
    }

    /** Pump flow rate, expressed in {@link #pumpRateUnit}. Informational / for the controller. */
    public double pumpRate = 1.0;

    public PumpRateUnit pumpRateUnit = PumpRateUnit.LPM;

    /** Reservoir volume (same unit as growth-space reservoir size) at/below which we alert. */
    public double minWaterLevel = 1.0;

    /** Raise a harvest alert when a bin is due within this many days. */
    public int harvestAlertDays = 3;

    /** When true the app talks to {@code MockHardwareGateway} instead of real Bluetooth. */
    public boolean useMockHardware = true;
}
