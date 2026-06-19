package ca.translucide.veggiegrow.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Root model object. The entire app state lives here and is what gets persisted to / exported as
 * JSON.
 */
public class AppData {

    /** Schema version, validated on import to guard against incompatible files. */
    public int schemaVersion = 1;

    /** True once first-run defaults (preset library + sample rack) have been seeded. */
    public boolean seeded = false;

    /**
     * Monotonic counter bumped on every push to a shared file. Used to detect when another device
     * has edited the shared library since this device last pulled, so pushes don't silently clobber.
     */
    public long revision = 0;

    /** Wall-clock time (epoch millis) of the last push to the shared file, 0 if never. */
    public long updatedAtEpochMillis = 0;

    /** Name of the person/device that performed the last push, null if never / unnamed. */
    public String lastEditedBy = null;

    public Settings settings = new Settings();

    public List<GrowthSpace> spaces = new ArrayList<>();

    public List<Preset> presets = new ArrayList<>();

    public AppData() {
    }

    /** Finds a space by code (case-insensitive), or null. */
    public GrowthSpace findSpace(String code) {
        if (code == null) return null;
        for (GrowthSpace s : spaces) {
            if (code.equalsIgnoreCase(s.code)) return s;
        }
        return null;
    }
}
