package ca.translucide.veggiegrow.imagesearch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wikimedia Commons file search. Anonymous, no API key (requires a User-Agent, set in {@link Http}).
 * Uses a search generator over the File namespace (6) and pulls a scaled thumb + original URL.
 */
public class WikimediaSource implements ImageSource {

    private static final String ENDPOINT =
            "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                    + "&generator=search&gsrnamespace=6&gsrlimit=20&prop=imageinfo"
                    + "&iiprop=url&iiurlwidth=300&gsrsearch=";

    @Override
    public String name() {
        return "Wikimedia";
    }

    @Override
    public List<ImageResult> search(String query) throws Exception {
        List<ImageResult> out = new ArrayList<>();
        JsonElement root = Http.getJson(ENDPOINT + Http.urlEncode(query));
        if (!root.isJsonObject()) return out;
        JsonObject pages = Json.obj(root.getAsJsonObject(), "query");
        pages = Json.obj(pages, "pages");
        if (pages == null) return out;

        for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject page = entry.getValue().getAsJsonObject();
            if (!page.has("imageinfo") || !page.get("imageinfo").isJsonArray()) continue;
            JsonElement first = page.getAsJsonArray("imageinfo").get(0);
            if (first == null || !first.isJsonObject()) continue;
            JsonObject info = first.getAsJsonObject();
            String thumb = Json.str(info, "thumburl");
            String full = Json.str(info, "url");
            if (thumb == null) thumb = full;
            if (full == null) continue;
            out.add(new ImageResult(thumb, full, Json.str(page, "title"),
                    "Wikimedia Commons", name()));
        }
        return out;
    }
}
