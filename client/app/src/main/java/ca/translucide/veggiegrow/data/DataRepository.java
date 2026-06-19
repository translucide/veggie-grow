package ca.translucide.veggiegrow.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import ca.translucide.veggiegrow.util.ImageUtils;

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
    /** Notified per-resource on every mutation so changes can be pushed to the REST API. */
    @Nullable
    private SyncListener syncListener;

    /**
     * Receives a callback for each individual resource change, so a backend can sync just what
     * changed rather than the whole model. Implemented by {@code CloudSync}. All callbacks fire on
     * the main thread, right after the change has been persisted locally.
     */
    public interface SyncListener {
        void onSpaceUpserted(@NonNull GrowthSpace space);

        void onSpaceDeleted(@NonNull String code);

        void onBinUpserted(@NonNull String spaceCode, @NonNull Bin bin);

        void onBinDeleted(@NonNull String spaceCode, @NonNull String binCode);

        void onPresetUpserted(@NonNull Preset preset);

        void onPresetDeleted(@NonNull String name);

        void onSettingsChanged(@NonNull Settings settings);
    }

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

    /** Registers the per-resource sync listener (see {@link SyncListener}). Pass null to detach. */
    public void setSyncListener(@Nullable SyncListener listener) {
        this.syncListener = listener;
    }

    // --- Mutations -----------------------------------------------------------------------------
    //
    // Every mutation persists locally (commit) and then notifies the sync listener with the single
    // resource that changed, so the backend pushes just that resource. "Update" variants carry the
    // previous code/name so a rename can delete the old key and create the new one (matching how
    // preset renames are handled at the call site).

    public void addSpace(GrowthSpace space) {
        data.spaces.add(space);
        commit();
        if (syncListener != null) syncListener.onSpaceUpserted(space);
    }

    /** Persists an in-place edit of an existing space. */
    public void updateSpace(GrowthSpace space) {
        updateSpace(space, null);
    }

    /** Persists an in-place edit of an existing space whose code may have changed from {@code previousCode}. */
    public void updateSpace(GrowthSpace space, @Nullable String previousCode) {
        commit();
        if (syncListener == null) return;
        if (previousCode != null && !previousCode.equalsIgnoreCase(space.code)) {
            // Renamed: the server keys spaces by code, so drop the old (cascading its bins) and
            // re-create the space with its bins under the new code.
            syncListener.onSpaceDeleted(previousCode);
            syncListener.onSpaceUpserted(space);
            if (space.bins != null) {
                for (Bin bin : space.bins) syncListener.onBinUpserted(space.code, bin);
            }
        } else {
            syncListener.onSpaceUpserted(space);
        }
    }

    public void removeSpace(GrowthSpace space) {
        data.spaces.remove(space);
        commit();
        if (syncListener != null) syncListener.onSpaceDeleted(space.code);
    }

    public void addBin(GrowthSpace space, Bin bin) {
        space.bins.add(bin);
        commit();
        if (syncListener != null) syncListener.onBinUpserted(space.code, bin);
    }

    /** Persists an in-place edit of an existing bin. */
    public void updateBin(GrowthSpace space, Bin bin) {
        updateBin(space, bin, null);
    }

    /** Persists an in-place edit of an existing bin whose code may have changed from {@code previousBinCode}. */
    public void updateBin(GrowthSpace space, Bin bin, @Nullable String previousBinCode) {
        commit();
        if (syncListener == null) return;
        if (previousBinCode != null && !previousBinCode.equalsIgnoreCase(bin.code)) {
            syncListener.onBinDeleted(space.code, previousBinCode);
        }
        syncListener.onBinUpserted(space.code, bin);
    }

    public void removeBin(GrowthSpace space, Bin bin) {
        space.bins.remove(bin);
        commit();
        if (syncListener != null) syncListener.onBinDeleted(space.code, bin.code);
    }

    public void markRefilled(GrowthSpace space, long nowMillis) {
        space.lastRefillEpochMillis = nowMillis;
        commit();
        if (syncListener != null) syncListener.onSpaceUpserted(space);
    }

    public void markHarvested(GrowthSpace space, Bin bin, long nowMillis) {
        bin.lastHarvestEpochMillis = nowMillis;
        commit();
        if (syncListener != null) syncListener.onBinUpserted(space.code, bin);
    }

    public void upsertPreset(Preset preset) {
        for (int i = 0; i < data.presets.size(); i++) {
            if (data.presets.get(i).name.equalsIgnoreCase(preset.name)) {
                data.presets.set(i, preset);
                commit();
                if (syncListener != null) syncListener.onPresetUpserted(preset);
                return;
            }
        }
        data.presets.add(preset);
        commit();
        if (syncListener != null) syncListener.onPresetUpserted(preset);
    }

    public void removePreset(Preset preset) {
        data.presets.remove(preset);
        commit();
        if (syncListener != null) syncListener.onPresetDeleted(preset.name);
    }

    /** Persists an in-place edit of the global settings. */
    public void updateSettings() {
        commit();
        if (syncListener != null) syncListener.onSettingsChanged(data.settings);
    }

    /** Replaces the whole model (used by import). */
    public void replaceAll(@NonNull AppData imported) {
        data.schemaVersion = imported.schemaVersion;
        data.seeded = true; // imported data stands on its own; never auto-seed over it
        data.revision = imported.revision;
        data.updatedAtEpochMillis = imported.updatedAtEpochMillis;
        data.lastEditedBy = imported.lastEditedBy;
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

    /**
     * Re-compresses any oversized embedded images (legacy data saved before the 256px limit) in place.
     * Returns true if anything changed. Heavy (image decode/encode) — call off the main thread, then
     * {@link #commit()} on the main thread to persist + notify.
     */
    public boolean compactOversizedImages() {
        boolean changed = false;
        for (GrowthSpace space : data.spaces) {
            String s = ImageUtils.recompressIfOversized(space.imageBase64);
            if (s != null) {
                space.imageBase64 = s;
                changed = true;
            }
            for (Bin bin : space.bins) {
                String b = ImageUtils.recompressIfOversized(bin.imageBase64);
                if (b != null) {
                    bin.imageBase64 = b;
                    changed = true;
                }
            }
        }
        for (Preset preset : data.presets) {
            String p = ImageUtils.recompressIfOversized(preset.imageBase64);
            if (p != null) {
                preset.imageBase64 = p;
                changed = true;
            }
        }
        return changed;
    }

}
