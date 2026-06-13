package ca.translucide.veggiegrow.imagesearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Openverse Creative-Commons image search. Anonymous, no API key.
 * {@code GET https://api.openverse.org/v1/images/?q=<query>&page_size=N}
 */
public class OpenverseSource implements ImageSource {

    private static final String ENDPOINT = "https://api.openverse.org/v1/images/?page_size=20&q=";

    @Override
    public String name() {
        return "Openverse";
    }

    @Override
    public List<ImageResult> search(String query) throws Exception {
        List<ImageResult> out = new ArrayList<>();
        JsonElement root = Http.getJson(ENDPOINT + Http.urlEncode(query));
        if (!root.isJsonObject()) return out;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("results") || !obj.get("results").isJsonArray()) return out;

        JsonArray results = obj.getAsJsonArray("results");
        for (JsonElement el : results) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            String thumb = Json.str(r, "thumbnail");
            String full = Json.str(r, "url");
            if (thumb == null) thumb = full;
            if (full == null) full = thumb;
            if (full == null) continue;
            out.add(new ImageResult(thumb, full, Json.str(r, "title"),
                    Json.str(r, "attribution"), name()));
        }
        return out;
    }
}
