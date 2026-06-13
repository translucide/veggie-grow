package ca.translucide.veggiegrow.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A growth space containing multiple {@link Bin}s and fed by a single water reservoir.
 */
public class GrowthSpace {

    /** Space code, e.g. "A". */
    public String code = "";

    public String name = "";

    /** Optional image, stored inline as base64 for portable export. */
    public String imageBase64;

    /** Reservoir capacity (litres or gallons, matching the settings pump-rate unit family). */
    public double waterReservoirSize;

    /** Timestamp of the last manual "refilled" action; basis for consumption estimates. 0 = never. */
    public long lastRefillEpochMillis;

    public List<Bin> bins = new ArrayList<>();

    public GrowthSpace() {
    }
}
