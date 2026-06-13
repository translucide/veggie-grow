package ca.translucide.veggiegrow.data;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

import ca.translucide.veggiegrow.data.model.AppData;

/**
 * Reads/writes the {@link AppData} model as a single JSON file. The same {@link Gson} instance is
 * reused for import/export so the on-disk format and the exported file are identical.
 */
public class JsonStore {

    public static final String FILE_NAME = "veggiegrow.json";

    private final File file;
    private final Gson gson;

    public JsonStore(@NonNull File baseDir) {
        this.file = new File(baseDir, FILE_NAME);
        this.gson = createGson();
    }

    /** Shared Gson configuration (pretty-printed for human-readable exports). */
    public static Gson createGson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    public Gson gson() {
        return gson;
    }

    /** Loads persisted data, or a fresh {@link AppData} if no file exists / parsing fails. */
    @NonNull
    public AppData load() {
        if (!file.exists()) {
            return new AppData();
        }
        try (Reader reader = new FileReader(file)) {
            AppData data = gson.fromJson(reader, AppData.class);
            return data != null ? data : new AppData();
        } catch (Exception e) {
            // Corrupt file: fall back to empty data rather than crashing on launch.
            return new AppData();
        }
    }

    public void save(@NonNull AppData data) throws IOException {
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        }
    }

    public String toJson(@NonNull AppData data) {
        return gson.toJson(data);
    }

    @NonNull
    public AppData fromJson(@NonNull String json) {
        AppData data = gson.fromJson(json, AppData.class);
        return data != null ? data : new AppData();
    }
}
