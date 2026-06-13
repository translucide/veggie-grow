package ca.translucide.veggiegrow.data.model;

/**
 * One point in a bin's watering schedule: starting at {@link #dayOffset} days after the bin's
 * start date, the watering {@link #rate} applies until a later point takes over. Only one point
 * is "current" at any given time.
 */
public class WateringRatePoint {

    /** Days after the bin start date at which this rate becomes active (>= 0). */
    public int dayOffset;

    /** Watering rate/level uploaded to the controller (e.g. 0.09). */
    public double rate;

    public WateringRatePoint() {
    }

    public WateringRatePoint(int dayOffset, double rate) {
        this.dayOffset = dayOffset;
        this.rate = rate;
    }
}
