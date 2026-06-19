package ca.translucide.veggiegrow.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.data.model.Settings;

/**
 * Thin, synchronous client for the VeggieGrow REST API (see the {@code server/} Go service). Each
 * domain object is its own resource:
 *
 * <ul>
 *   <li>{@code GET/PUT/DELETE /v1/spaces/{code}} (and nested {@code .../bins/{binCode}})</li>
 *   <li>{@code GET/PUT/DELETE /v1/presets/{name}}</li>
 *   <li>{@code GET/PUT /v1/settings}</li>
 * </ul>
 *
 * The JSON the server emits matches the app's Gson model field-for-field (it carries a couple of
 * extra server-only fields — {@code rev}, {@code updatedAtEpochMillis} — which Gson simply ignores
 * on the way in and never sends on the way out). All methods block and are meant to be called off
 * the main thread; failures throw {@link IOException}.
 */
class ApiClient {

    private static final String USER_AGENT = "VeggieGrow/1.0 (Android; info@translucide.ca)";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final String baseUrl; // normalised, always ends with '/'
    private final TokenProvider tokenProvider;
    private final Gson gson = new Gson();

    ApiClient(@NonNull String baseUrl, @NonNull TokenProvider tokenProvider) {
        String b = baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b : b + "/";
        this.tokenProvider = tokenProvider;
    }

    // --- spaces ----------------------------------------------------------------------------------

    /** Lists all spaces, each with its bins embedded (as the server returns them). */
    @NonNull
    List<GrowthSpace> getSpaces() throws IOException {
        String body = send("GET", url("spaces"), null, false);
        SpacesEnvelope env = gson.fromJson(body, SpacesEnvelope.class);
        return env != null && env.spaces != null ? env.spaces : new ArrayList<>();
    }

    /** Upserts a space (its bins are managed separately and are not sent here). */
    void putSpace(@NonNull GrowthSpace space) throws IOException {
        send("PUT", url("spaces/" + enc(space.code)), gson.toJson(withoutBins(space)), false);
    }

    void deleteSpace(@NonNull String code) throws IOException {
        send("DELETE", url("spaces/" + enc(code)), null, true);
    }

    // --- bins ------------------------------------------------------------------------------------

    void putBin(@NonNull String spaceCode, @NonNull Bin bin) throws IOException {
        send("PUT", url("spaces/" + enc(spaceCode) + "/bins/" + enc(bin.code)), gson.toJson(bin), false);
    }

    void deleteBin(@NonNull String spaceCode, @NonNull String binCode) throws IOException {
        send("DELETE", url("spaces/" + enc(spaceCode) + "/bins/" + enc(binCode)), null, true);
    }

    // --- presets ---------------------------------------------------------------------------------

    @NonNull
    List<Preset> getPresets() throws IOException {
        String body = send("GET", url("presets"), null, false);
        PresetsEnvelope env = gson.fromJson(body, PresetsEnvelope.class);
        return env != null && env.presets != null ? env.presets : new ArrayList<>();
    }

    void putPreset(@NonNull Preset preset) throws IOException {
        send("PUT", url("presets/" + enc(preset.name)), gson.toJson(preset), false);
    }

    void deletePreset(@NonNull String name) throws IOException {
        send("DELETE", url("presets/" + enc(name)), null, true);
    }

    // --- settings --------------------------------------------------------------------------------

    @NonNull
    Settings getSettings() throws IOException {
        String body = send("GET", url("settings"), null, false);
        Settings s = gson.fromJson(body, Settings.class);
        return s != null ? s : new Settings();
    }

    void putSettings(@NonNull Settings settings) throws IOException {
        send("PUT", url("settings"), gson.toJson(settings), false);
    }

    // --- identity / account / members ------------------------------------------------------------

    /** GET /v1/me — identity + account state (or needsOnboarding / tokenStale). */
    @NonNull
    AccountInfo getMe() throws IOException {
        String body = send("GET", url("me"), null, false);
        AccountInfo info = gson.fromJson(body, AccountInfo.class);
        return info != null ? info : new AccountInfo();
    }

    /** POST /v1/accounts — create an account; the caller becomes its owner. */
    @NonNull
    AccountInfo createAccount(@NonNull String name) throws IOException {
        String body = send("POST", url("accounts"), gson.toJson(new NameBody(name)), false);
        AccountInfo info = gson.fromJson(body, AccountInfo.class);
        return info != null ? info : new AccountInfo();
    }

    @NonNull
    MembersList listMembers() throws IOException {
        String body = send("GET", url("account/members"), null, false);
        MembersList ml = gson.fromJson(body, MembersList.class);
        return ml != null ? ml : new MembersList();
    }

    void inviteMember(@NonNull String email, @NonNull String role) throws IOException {
        send("POST", url("account/members"), gson.toJson(new InviteBody(email, role)), false);
    }

    void updateMemberRole(@NonNull String uid, @NonNull String role) throws IOException {
        send("PUT", url("account/members/" + enc(uid)), gson.toJson(new RoleBody(role)), false);
    }

    void removeMember(@NonNull String uid) throws IOException {
        send("DELETE", url("account/members/" + enc(uid)), null, true);
    }

    void cancelInvite(@NonNull String email) throws IOException {
        send("DELETE", url("account/invites/" + enc(email)), null, true);
    }

    // --- HTTP --------------------------------------------------------------------------------------

    /**
     * Performs the request and returns the response body (empty string for an empty body). When
     * {@code allow404} is set, a 404 is treated as success and yields {@code null} (used for DELETEs
     * of already-absent resources); otherwise any non-2xx status throws.
     */
    @Nullable
    private String send(@NonNull String method, @NonNull String url, @Nullable String jsonBody,
                        boolean allow404) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            String token = tokenProvider.token();
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                    out.flush();
                }
            }

            int code = conn.getResponseCode();
            if (allow404 && code == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (code < 200 || code >= 300) {
                throw new IOException("Server returned HTTP " + code + " for " + method + " " + url);
            }
            return readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @NonNull
    private String url(@NonNull String suffix) {
        return baseUrl + suffix;
    }

    /** URL-encodes a single path segment (e.g. a preset name containing spaces). */
    @NonNull
    private static String enc(@NonNull String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return segment;
        }
    }

    /** Shallow copy of a space with its bins dropped, so PUT /spaces only carries the space's own fields. */
    @NonNull
    private static GrowthSpace withoutBins(@NonNull GrowthSpace s) {
        GrowthSpace c = new GrowthSpace();
        c.code = s.code;
        c.name = s.name;
        c.imageBase64 = s.imageBase64;
        c.waterReservoirSize = s.waterReservoirSize;
        c.lastRefillEpochMillis = s.lastRefillEpochMillis;
        c.bins = null; // Gson omits nulls -> "bins" is not sent
        return c;
    }

    @NonNull
    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static class SpacesEnvelope {
        List<GrowthSpace> spaces;
    }

    private static class PresetsEnvelope {
        List<Preset> presets;
    }

    // Request bodies.
    private static class NameBody {
        final String name;
        NameBody(String name) { this.name = name; }
    }

    private static class InviteBody {
        final String email;
        final String role;
        InviteBody(String email, String role) { this.email = email; this.role = role; }
    }

    private static class RoleBody {
        final String role;
        RoleBody(String role) { this.role = role; }
    }
}
