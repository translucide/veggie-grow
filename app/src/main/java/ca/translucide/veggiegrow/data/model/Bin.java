package ca.translucide.veggiegrow.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A growth bin within a {@link GrowthSpace}. Referenced in the UI and controller protocol as
 * "<space code><bin code>", e.g. space "A" + bin "1" => "A1".
 */
public class Bin {

    /** Bin code, e.g. "1". Combined with the space code to form "A1". */
    public String code = "";

    public String varietyName = "";

    /** Optional image, stored inline as a base64-encoded JPEG/PNG for portable export. */
    public String imageBase64;

    /** Bin start date (epoch millis, local midnight). */
    public long startDateEpochMillis;

    /** Time-varying watering rate. Sorted ascending by dayOffset when evaluated. */
    public List<WateringRatePoint> wateringSchedule = new ArrayList<>();

    /** Days after start date for the first harvest. */
    public int firstHarvestDays;

    /** Repeat harvest every N days after the first harvest. 0 disables recurring harvests. */
    public int harvestIntervalDays;

    public Bin() {
    }
}
