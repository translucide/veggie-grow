package ca.translucide.veggiegrow.data;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.IOException;

/**
 * Bearer token = the signed-in user's Firebase ID token. {@code getIdToken(false)} returns the
 * cached token, transparently refreshing it when it's near expiry, so callers always get a valid
 * JWT. Blocks via {@link Tasks#await}, so it must run off the main thread (it does — every API call
 * runs on {@link CloudSync}'s worker or {@link AccountManager}'s executor).
 */
class FirebaseTokenProvider implements TokenProvider {

    @Nullable
    @Override
    public String token() throws IOException {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return null;
        try {
            return Tasks.await(user.getIdToken(false)).getToken();
        } catch (Exception e) {
            throw new IOException("Could not obtain Firebase ID token", e);
        }
    }

    /** Forces a fresh ID token (used after the server changes our custom claims). */
    static void forceRefresh() throws IOException {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        try {
            Tasks.await(user.getIdToken(true));
        } catch (Exception e) {
            throw new IOException("Could not refresh Firebase ID token", e);
        }
    }
}
