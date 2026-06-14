package ca.translucide.veggiegrow.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.util.List;

import ca.translucide.veggiegrow.data.model.AppData;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.util.DateUtils;

/**
 * Single source of truth for application state. Holds the {@link AppData} model in memory, exposes
 * it as {@link LiveData}, and persists every mutation to a JSON file via {@link JsonStore}.
 *
 * <p>Process-wide singleton, initialised once from {@code VeggieGrowApp}.
 */
public class DataRepository {

    private static final String TAG = "DataRepository";

    private static DataRepository instance;

    /** Codes of the 12 default bins seeded on first run (one preset each). */
    private static final String[] DEFAULT_BIN_VARIETIES = {
            "Cherry Tomato", "Bell Pepper", "Slicing Cucumber", "Zucchini",
            "Butterhead Lettuce", "Curly Kale", "Nantes Carrot", "Cherry Belle Radish",
            "Bush Bean", "Genovese Basil", "Italian Parsley", "Strawberry"
    };
    private static final double DEFAULT_RESERVOIR_ML = 20000.0; // 20 L

    private final JsonStore store;
    private final AppData data;
    private final MutableLiveData<AppData> liveData = new MutableLiveData<>();
    private boolean seededThisLaunch;

    private DataRepository(@NonNull Context appContext) {
        this.store = new JsonStore(appContext.getFilesDir());
        this.data = store.load();
        if (!data.seeded && data.spaces.isEmpty() && data.presets.isEmpty()) {
            seedDefaults(appContext);
        }
        liveData.setValue(data);
    }

    /** True when this launch performed the first-run seeding (used to fetch default photos once). */
    public boolean wasSeededThisLaunch() {
        return seededThisLaunch;
    }

    /**
     * First-run defaults: load the bundled preset library, then create space "A" with 12 bins
     * (codes 1..12), each instantiated from a preset, starting today.
     */
    private void seedDefaults(@NonNull Context context) {
        List<Preset> library = PresetLibrary.load(context);
        if (library.isEmpty()) {
            return; // nothing to seed from; try again next launch
        }
        data.presets.addAll(library);

        GrowthSpace space = new GrowthSpace();
        space.code = "A";
        space.name = "My Growth Space";
        space.waterReservoirSize = DEFAULT_RESERVOIR_ML; // image left null -> leaf icon shown

        long today = DateUtils.todayMillis();
        int code = 1;
        for (String variety : DEFAULT_BIN_VARIETIES) {
            Preset preset = findPreset(library, variety);
            if (preset == null) continue;
            Bin bin = new Bin();
            bin.code = String.valueOf(code++);
            preset.applyTo(bin);
            bin.startDateEpochMillis = today;
            space.bins.add(bin);
        }
        data.spaces.add(space);
        data.seeded = true;
        seededThisLaunch = true;

        try {
            store.save(data);
        } catch (IOException e) {
            Log.e(TAG, "Failed to persist seeded defaults", e);
        }
    }

    private static Preset findPreset(List<Preset> presets, String name) {
        for (Preset p : presets) {
            if (name.equalsIgnoreCase(p.name)) return p;
        }
        return null;
    }

    public static synchronized DataRepository init(@NonNull Context context) {
        if (instance == null) {
            instance = new DataRepository(context.getApplicationContext());
        }
        return instance;
    }

    public static DataRepository get() {
        if (instance == null) {
            throw new IllegalStateException("DataRepository.init() must be called first (in Application.onCreate)");
        }
        return instance;
    }

    public JsonStore store() {
        return store;
    }

    public LiveData<AppData> liveData() {
        return liveData;
    }

    /** Direct access to the in-memory model. Call {@link #commit()} after mutating it. */
    @NonNull
    public AppData data() {
        return data;
    }

    public Settings settings() {
        return data.settings;
    }

    // --- Mutations -----------------------------------------------------------------------------

    public void addSpace(GrowthSpace space) {
        data.spaces.add(space);
        commit();
    }

    public void removeSpace(GrowthSpace space) {
        data.spaces.remove(space);
        commit();
    }

    public void addBin(GrowthSpace space, Bin bin) {
        space.bins.add(bin);
        commit();
    }

    public void removeBin(GrowthSpace space, Bin bin) {
        space.bins.remove(bin);
        commit();
    }

    public void markRefilled(GrowthSpace space, long nowMillis) {
        space.lastRefillEpochMillis = nowMillis;
        commit();
    }

    public void markHarvested(Bin bin, long nowMillis) {
        bin.lastHarvestEpochMillis = nowMillis;
        commit();
    }

    public void upsertPreset(Preset preset) {
        for (int i = 0; i < data.presets.size(); i++) {
            if (data.presets.get(i).name.equalsIgnoreCase(preset.name)) {
                data.presets.set(i, preset);
                commit();
                return;
            }
        }
        data.presets.add(preset);
        commit();
    }

    public void removePreset(Preset preset) {
        data.presets.remove(preset);
        commit();
    }

    /** Replaces the whole model (used by import). */
    public void replaceAll(@NonNull AppData imported) {
        data.schemaVersion = imported.schemaVersion;
        data.seeded = true; // imported data stands on its own; never auto-seed over it
        data.settings = imported.settings != null ? imported.settings : new Settings();
        data.spaces.clear();
        if (imported.spaces != null) data.spaces.addAll(imported.spaces);
        data.presets.clear();
        if (imported.presets != null) data.presets.addAll(imported.presets);
        commit();
    }

    /** Persists the current model and notifies observers. Call after any direct mutation. */
    public void commit() {
        liveData.setValue(data);
        try {
            store.save(data);
        } catch (IOException e) {
            Log.e(TAG, "Failed to persist data", e);
        }
    }
}
