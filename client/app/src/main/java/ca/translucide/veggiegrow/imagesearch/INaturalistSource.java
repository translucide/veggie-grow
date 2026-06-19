package ca.translucide.veggiegrow.imagesearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * iNaturalist taxa autocomplete. Anonymous, no API key. Returns real species photos.
 * {@code GET https://api.inaturalist.org/v1/taxa/autocomplete?q=<query>}
 */
public class INaturalistSource implements ImageSource {

    private static final String ENDPOINT =
            "https://api.inaturalist.org/v1/taxa/autocomplete?per_page=20&q=";

    @Override
    public String name() {
        return "iNaturalist";
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
            JsonObject taxon = el.getAsJsonObject();
            JsonObject photo = Json.obj(taxon, "default_photo");
            if (photo == null) continue;
            String thumb = Json.str(photo, "square_url");
            String medium = Json.str(photo, "medium_url");
            String full = medium != null ? medium : Json.str(photo, "url");
            if (thumb == null) thumb = full;
            if (full == null) continue;
            String title = Json.str(taxon, "preferred_common_name");
            if (title == null) title = Json.str(taxon, "name");
            out.add(new ImageResult(thumb, full, title, Json.str(photo, "attribution"), name()));
        }
        return out;
    }
}
