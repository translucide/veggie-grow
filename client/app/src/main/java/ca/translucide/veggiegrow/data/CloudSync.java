package ca.translucide.veggiegrow.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.firebase.auth.FirebaseAuth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ca.translucide.veggiegrow.data.model.AppData;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.data.model.Settings;

/**
 * Keeps the local model in sync with the shared REST API ({@link ApiClient}) as a real, per-resource
 * service rather than a whole-file blob.
 *
 * <p><b>Write-through:</b> implemented as {@link DataRepository.SyncListener} — every local mutation
 * pushes just that one resource ({@code PUT}/{@code DELETE}) on a single background thread, so writes
 * keep their order. A failed write is parked and retried on the next sync tick, so a brief network
 * blip doesn't lose an edit.
 *
 * <p><b>Pull:</b> while the app is in the foreground it polls the server (and pulls once on every
 * resume), replacing the local model with the server's, so a change made on another device shows up
 * here. A pull is skipped whenever local writes are still pending, so it never clobbers an edit that
 * hasn't reached the server yet.
 *
 * <p><b>Bootstrap:</b> on first foreground, if the server is empty the local (seeded) library is
 * uploaded — the first device to run defines the shared library; later devices pull it. Because every
 * resource is keyed by its natural code/name, two devices seeding at once converge instead of
 * duplicating.
 */
public class CloudSync implements DataRepository.SyncListener {

    private static final String TAG = "CloudSync";

    /** How often to poll the server for others' changes while in the foreground. */
    private static final long POLL_INTERVAL_MS = 20_000L;

    private final DataRepository repository;
    private final SyncConfig config;
    @Nullable
    private final ApiClient api;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Serialises every network operation (writes, pulls, uploads) so their order is preserved. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cloud-sync");
        t.setDaemon(true);
        return t;
    });
    /** Writes still in flight, plus parked writes awaiting retry. */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentLinkedDeque<Write> failed = new ConcurrentLinkedDeque<>();

    private boolean lifecycleAttached;
    private boolean didInitialSync;
    /** Enabled only once the user is signed in and a member of an account (set by the auth flow). */
    private volatile boolean enabled;
    /** JSON of the last server state we applied; lets a poll skip the model swap when nothing changed.
     *  Only ever touched on the single {@link #io} thread. */
    @Nullable
    private String lastServerJson;

    public CloudSync(@NonNull Context context, @NonNull DataRepository repository) {
        Context appContext = context.getApplicationContext();
        this.repository = repository;
        this.config = SyncConfig.load(appContext);
        this.api = config.isConfigured() ? new ApiClient(config.baseUrl, new FirebaseTokenProvider()) : null;
    }

    /** Result of an async {@link #pullAsync}. */
    public interface PullCallback {
        /** {@code pulled} is non-null on success; otherwise {@code error} explains the failure. */
        void onComplete(@Nullable AppData pulled, @Nullable Exception error);
    }

    private static final PullCallback SILENT_PULL = (pulled, error) -> { };

    // --- config / device-local state -------------------------------------------------------------

    public boolean isConfigured() {
        return api != null;
    }

    @NonNull
    public String serverLabel() {
        return config.label();
    }

    /** True while any local write has not yet been confirmed by the server. */
    public boolean hasPendingWrites() {
        return inFlight.get() > 0 || !failed.isEmpty();
    }

    /** True once syncing should run: enabled by the auth flow, configured, and a user is signed in. */
    private boolean ready() {
        return enabled && api != null && FirebaseAuth.getInstance().getCurrentUser() != null;
    }

    /**
     * Turns syncing on/off. The auth flow calls {@code setEnabled(true)} once the user is signed in
     * and belongs to an account, and {@code setEnabled(false)} on sign-out. Enabling kicks off a
     * sync immediately; disabling stops polling and resets bootstrap state so the next account
     * starts clean.
     */
    public void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            startSync();
        } else {
            stopPolling();
            didInitialSync = false;
            lastServerJson = null;
        }
    }

    // --- lifecycle: bootstrap + foreground polling -----------------------------------------------

    /**
     * Wires the sync into the process lifecycle: while enabled, pull on every resume and poll
     * periodically in the foreground. Call once, from the main thread.
     */
    public void attachLifecycle() {
        if (lifecycleAttached || !isConfigured()) return;
        lifecycleAttached = true;
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                startSync();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                stopPolling();
            }
        });
    }

    /** Brings sync up: first foreground after enable bootstraps; later ones just tick. */
    private void startSync() {
        if (!ready()) return;
        if (!didInitialSync) {
            didInitialSync = true;
            initialSync();
        } else {
            syncTick();
        }
        startPolling();
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            syncTick();
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void startPolling() {
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        mainHandler.removeCallbacks(pollRunnable);
    }

    /** One periodic step: retry any parked writes, then pull (the pull no-ops if writes remain). */
    private void syncTick() {
        if (!ready()) return;
        retryFailedWrites();
        pullAsync(SILENT_PULL);
    }

    // --- pull ------------------------------------------------------------------------------------

    /**
     * Fetches the whole model from the server and replaces the local model with it. Skipped (reported
     * as an error to the callback) while local writes are still pending, so it never overwrites an
     * edit that hasn't been uploaded yet. The callback fires on the main thread.
     */
    public void pullAsync(@NonNull PullCallback cb) {
        if (api == null) {
            mainHandler.post(() -> cb.onComplete(null, new IllegalStateException("Sync is not configured")));
            return;
        }
        io.execute(() -> {
            if (hasPendingWrites()) {
                mainHandler.post(() -> cb.onComplete(null,
                        new IOException("Local changes are still syncing; skipped pull")));
                return;
            }
            fetchAndApply(cb);
        });
    }

    /**
     * On the {@link #io} thread: fetch the whole model and replace the local model with it — but only
     * if the server state actually changed since the last apply, so an unchanged poll doesn't churn
     * the UI. The callback (if any) fires on the main thread.
     */
    private void fetchAndApply(@Nullable PullCallback cb) {
        if (api == null) return;
        try {
            AppData data = assemble(api.getSpaces(), api.getPresets(), api.getSettings());
            String json = repository.store().toJson(data);
            boolean changed = !json.equals(lastServerJson);
            lastServerJson = json;
            mainHandler.post(() -> {
                if (changed) repository.replaceAll(data);
                if (cb != null) cb.onComplete(data, null);
            });
        } catch (Exception e) {
            mainHandler.post(() -> {
                if (cb != null) cb.onComplete(null, e);
            });
        }
    }

    /**
     * Assembles fetched resources into an {@link AppData} (spaces already carry their bins). The
     * server returns each collection in document-id (lexicographic) order; we re-sort by natural
     * key so numeric codes read 1,2,…,10 rather than 1,10,11,2, and so the order is stable between
     * polls (which the change-detection diff relies on).
     */
    @NonNull
    private AppData assemble(@NonNull List<GrowthSpace> spaces, @NonNull List<Preset> presets,
                             @NonNull Settings settings) {
        Collections.sort(spaces, (a, b) -> compareKeys(a.code, b.code));
        for (GrowthSpace s : spaces) {
            if (s.bins != null) Collections.sort(s.bins, (a, b) -> compareKeys(a.code, b.code));
        }
        Collections.sort(presets, (a, b) -> compareKeys(a.name, b.name));

        AppData data = new AppData();
        data.schemaVersion = 1;
        data.seeded = true;
        data.settings = settings;
        data.spaces = spaces;
        data.presets = presets;
        return data;
    }

    /** Orders two natural keys numerically when both are integers, else case-insensitively. */
    private static int compareKeys(@Nullable String a, @Nullable String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        try {
            return Long.compare(Long.parseLong(x), Long.parseLong(y));
        } catch (NumberFormatException ignored) {
            return x.compareToIgnoreCase(y);
        }
    }

    // --- bootstrap / bulk upload -----------------------------------------------------------------

    private void initialSync() {
        if (!ready()) return;
        io.execute(() -> {
            try {
                List<GrowthSpace> spaces = api.getSpaces();
                List<Preset> presets = api.getPresets();
                Settings settings = api.getSettings();
                if (spaces.isEmpty() && presets.isEmpty()) {
                    // Empty server: seed it from our local model (first device to run).
                    mainHandler.post(this::snapshotAndPushAll);
                } else {
                    AppData data = assemble(spaces, presets, settings);
                    lastServerJson = repository.store().toJson(data);
                    mainHandler.post(() -> repository.replaceAll(data));
                }
            } catch (Exception e) {
                Log.w(TAG, "Initial sync failed; will retry on next foreground", e);
            }
        });
    }

    /**
     * Snapshots the local model on the main thread, then uploads it (upsert) off the main thread.
     * Used once at startup to seed a fresh/empty server with the first device's library.
     */
    private void snapshotAndPushAll() {
        if (api == null) return;
        AppData d = repository.data();
        List<GrowthSpace> spaces = new ArrayList<>(d.spaces);
        List<Preset> presets = new ArrayList<>(d.presets);
        Settings settings = d.settings;
        io.execute(() -> {
            try {
                api.putSettings(settings);
                for (GrowthSpace s : spaces) {
                    api.putSpace(s);
                    List<Bin> bins = s.bins != null ? new ArrayList<>(s.bins) : new ArrayList<>();
                    for (Bin b : bins) api.putBin(s.code, b);
                }
                for (Preset p : presets) api.putPreset(p);
            } catch (Exception e) {
                Log.w(TAG, "Seeding server from local failed; will retry on next foreground", e);
            }
        });
    }

    // --- write-through (DataRepository.SyncListener) ---------------------------------------------

    @Override
    public void onSpaceUpserted(@NonNull GrowthSpace space) {
        submitWrite("space:" + space.code, api -> api.putSpace(space));
    }

    @Override
    public void onSpaceDeleted(@NonNull String code) {
        submitWrite("space-del:" + code, api -> api.deleteSpace(code));
    }

    @Override
    public void onBinUpserted(@NonNull String spaceCode, @NonNull Bin bin) {
        submitWrite("bin:" + spaceCode + "/" + bin.code, api -> api.putBin(spaceCode, bin));
    }

    @Override
    public void onBinDeleted(@NonNull String spaceCode, @NonNull String binCode) {
        submitWrite("bin-del:" + spaceCode + "/" + binCode, api -> api.deleteBin(spaceCode, binCode));
    }

    @Override
    public void onPresetUpserted(@NonNull Preset preset) {
        submitWrite("preset:" + preset.name, api -> api.putPreset(preset));
    }

    @Override
    public void onPresetDeleted(@NonNull String name) {
        submitWrite("preset-del:" + name, api -> api.deletePreset(name));
    }

    @Override
    public void onSettingsChanged(@NonNull Settings settings) {
        submitWrite("settings", api -> api.putSettings(settings));
    }

    // --- write queue -----------------------------------------------------------------------------

    private interface IoTask {
        void run(@NonNull ApiClient api) throws IOException;
    }

    private static final class Write {
        final String desc;
        final IoTask task;

        Write(String desc, IoTask task) {
            this.desc = desc;
            this.task = task;
        }
    }

    private void submitWrite(@NonNull String desc, @NonNull IoTask task) {
        if (api == null || !enabled) return;
        inFlight.incrementAndGet();
        io.execute(() -> {
            try {
                task.run(api);
            } catch (Exception e) {
                failed.add(new Write(desc, task));
                Log.w(TAG, "Write failed, parked for retry: " + desc, e);
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    /** Re-submits parked writes (in original order) on the next tick. */
    private void retryFailedWrites() {
        Write w;
        while ((w = failed.poll()) != null) {
            submitWrite(w.desc, w.task);
        }
    }
}
