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
