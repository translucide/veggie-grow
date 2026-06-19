package ca.translucide.veggiegrow.imagesearch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Null-safe accessors over Gson's tree model, to keep the source parsers terse.
 */
final class Json {

    private Json() {
    }

    static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement e = obj.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static JsonObject obj(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        return parent.getAsJsonObject(key);
    }
}
