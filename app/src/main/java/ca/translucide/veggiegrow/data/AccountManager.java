package ca.translucide.veggiegrow.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.firebase.ui.auth.AuthUI;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UI-facing facade for authentication, onboarding and member management. Wraps {@link ApiClient}
 * (identity/account/member endpoints) and Firebase Auth, running network work off the main thread
 * and delivering results back on it.
 *
 * <p>When the server changes our custom claims (creating an account, accepting an invite) it sets
 * {@code tokenStale}; this manager force-refreshes the Firebase ID token before returning, so the
 * caller's next request already carries the new {@code accountId}/{@code role}.
 */
public class AccountManager {

    private final ApiClient api;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "account-mgr");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    public AccountManager(@NonNull Context context) {
        SyncConfig config = SyncConfig.load(context.getApplicationContext());
        this.api = new ApiClient(config.baseUrl, new FirebaseTokenProvider());
    }

    /** Async result: exactly one of {@code value}/{@code error} is non-null. Fires on the main thread. */
    public interface Callback<T> {
        void onResult(@Nullable T value, @Nullable Exception error);
    }

    // --- auth state ------------------------------------------------------------------------------

    public boolean isSignedIn() {
        return FirebaseAuth.getInstance().getCurrentUser() != null;
    }

    @Nullable
    public String currentEmail() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u == null ? null : u.getEmail();
    }

    /** Signs out of Firebase (and any federated provider); {@code done} runs on the main thread. */
    public void signOut(@NonNull Context context, @NonNull Runnable done) {
        AuthUI.getInstance().signOut(context.getApplicationContext())
                .addOnCompleteListener(t -> done.run());
    }

    // --- onboarding ------------------------------------------------------------------------------

    /** GET /v1/me — resolves identity + account state (auto-accepting a matching invite server-side). */
    public void fetchMe(@NonNull Callback<AccountInfo> cb) {
        io.execute(() -> {
            try {
                AccountInfo info = api.getMe();
                if (info.tokenStale) {
                    FirebaseTokenProvider.forceRefresh();
                    info = api.getMe();
                }
                deliver(cb, info, null);
            } catch (Exception e) {
                deliver(cb, null, e);
            }
        });
    }

    /** POST /v1/accounts — create an account and become its owner. */
    public void createAccount(@NonNull String name, @NonNull Callback<AccountInfo> cb) {
        io.execute(() -> {
            try {
                AccountInfo info = api.createAccount(name);
                if (info.tokenStale) {
                    FirebaseTokenProvider.forceRefresh();
                }
                deliver(cb, info, null);
            } catch (Exception e) {
                deliver(cb, null, e);
            }
        });
    }

    // --- member management (owner) ---------------------------------------------------------------

    public void listMembers(@NonNull Callback<MembersList> cb) {
        io.execute(() -> {
            try {
                deliver(cb, api.listMembers(), null);
            } catch (Exception e) {
                deliver(cb, null, e);
            }
        });
    }

    public void inviteMember(@NonNull String email, @NonNull String role, @NonNull Callback<Void> cb) {
        run(cb, () -> api.inviteMember(email, role));
    }

    public void updateMemberRole(@NonNull String uid, @NonNull String role, @NonNull Callback<Void> cb) {
        run(cb, () -> api.updateMemberRole(uid, role));
    }

    public void removeMember(@NonNull String uid, @NonNull Callback<Void> cb) {
        run(cb, () -> api.removeMember(uid));
    }

    public void cancelInvite(@NonNull String email, @NonNull Callback<Void> cb) {
        run(cb, () -> api.cancelInvite(email));
    }

    // --- plumbing --------------------------------------------------------------------------------

    private interface IoAction {
        void run() throws Exception;
    }

    private void run(@NonNull Callback<Void> cb, @NonNull IoAction action) {
        io.execute(() -> {
            try {
                action.run();
                deliver(cb, null, null);
            } catch (Exception e) {
                deliver(cb, null, e);
            }
        });
    }

    private <T> void deliver(@NonNull Callback<T> cb, @Nullable T value, @Nullable Exception error) {
        main.post(() -> cb.onResult(value, error));
    }
}
