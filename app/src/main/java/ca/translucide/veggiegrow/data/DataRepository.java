package ca.translucide.veggiegrow.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;

import ca.translucide.veggiegrow.data.model.AppData;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.data.model.Settings;

/**
 * Single source of truth for application state. Holds the {@link AppData} model in memory, exposes
 * it as {@link LiveData}, and persists every mutation to a JSON file via {@link JsonStore}.
 *
 * <p>Process-wide singleton, initialised once from {@code VeggieGrowApp}.
 */
public class DataRepository {

    private static final String TAG = "DataRepository";

    private static DataRepository instance;

    private final JsonStore store;
    private final AppData data;
    private final MutableLiveData<AppData> liveData = new MutableLiveData<>();

    private DataRepository(@NonNull Context appContext) {
        this.store = new JsonStore(appContext.getFilesDir());
        this.data = store.load();
        liveData.setValue(data);
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
