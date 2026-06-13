package ca.translucide.veggiegrow.data;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import ca.translucide.veggiegrow.data.model.AppData;

/**
 * Exports the model to / imports it from a user-chosen JSON file via the Storage Access Framework.
 * The caller obtains the {@link Uri} from {@code ACTION_CREATE_DOCUMENT} / {@code ACTION_OPEN_DOCUMENT}.
 */
public class ImportExportManager {

    /** Maximum schema version this build understands. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public static final String MIME_TYPE = "application/json";
    public static final String SUGGESTED_FILENAME = "veggiegrow-export.json";

    private final DataRepository repository;

    public ImportExportManager(@NonNull DataRepository repository) {
        this.repository = repository;
    }

    /** Writes the current model as JSON to the given document uri. */
    public void exportTo(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
        String json = repository.store().toJson(repository.data());
        try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Unable to open output stream for export");
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * Reads and validates JSON from the given uri, then replaces the in-memory model.
     *
     * @throws IOException              on read failure
     * @throws IllegalArgumentException if the file is not valid / supported app data
     */
    public void importFrom(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
        String json = readAll(resolver, uri);
        AppData imported;
        try {
            imported = repository.store().fromJson(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("File is not valid VeggieGrow JSON", e);
        }
        if (imported.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "File schema version " + imported.schemaVersion
                            + " is newer than supported (" + SUPPORTED_SCHEMA_VERSION + ")");
        }
        repository.replaceAll(imported);
    }

    private String readAll(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Unable to open input stream for import");
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }
}
