package ca.translucide.veggiegrow.data;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Deployment config for the self-hosted shared-library endpoint, loaded from the bundled
 * {@code assets/sync_config.json} so the server URL is never hard-coded in source. Edit that file and
 * rebuild to point the app at a different server.
 *
 * <p>Contract the server must honour:
 * <ul>
 *   <li>{@code GET {baseUrl}/{fileName}} returns the JSON model (200), or 404 if none exists yet.</li>
 *   <li>{@code PUT {baseUrl}/{fileName}} stores the JSON request body.</li>
 *   <li>If {@link #authHeader} is set, it is sent verbatim as the {@code Authorization} header.</li>
 * </ul>
 */
public class SyncConfig {

    private static final String ASSET_NAME = "sync_config.json";
    private static final String DEFAULT_FILE_NAME = "veggiegrow.json";

    public String baseUrl = "";
    public String authHeader = "";
    public String fileName = DEFAULT_FILE_NAME;

    /** Loads the bundled config; returns an unconfigured instance if the asset is missing/invalid. */
    @NonNull
    public static SyncConfig load(@NonNull Context context) {
        try (InputStream in = context.getAssets().open(ASSET_NAME);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            SyncConfig cfg = new Gson().fromJson(reader, SyncConfig.class);
            return cfg != null ? cfg : new SyncConfig();
        } catch (Exception e) {
            return new SyncConfig();
        }
    }

    /** True once a server URL has been provided in the config file. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.trim().isEmpty();
    }

    /** Full URL of the shared data file ({@code baseUrl} + {@code fileName}). */
    @NonNull
    public String fileUrl() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (!base.endsWith("/")) base = base + "/";
        String name = (fileName == null || fileName.trim().isEmpty()) ? DEFAULT_FILE_NAME : fileName.trim();
        return base + name;
    }

    /** Human-readable server label for the settings status line. */
    @NonNull
    public String label() {
        return isConfigured() ? baseUrl.trim() : "";
    }
}
