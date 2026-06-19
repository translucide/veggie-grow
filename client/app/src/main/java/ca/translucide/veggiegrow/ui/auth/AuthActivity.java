package ca.translucide.veggiegrow.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract;
import com.firebase.ui.auth.data.model.FirebaseAuthUIAuthenticationResult;

import java.util.Arrays;
import java.util.List;

import ca.translucide.veggiegrow.VeggieGrowApp;
import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.AccountInfo;
import ca.translucide.veggiegrow.data.AccountManager;
import ca.translucide.veggiegrow.data.CloudSync;
import ca.translucide.veggiegrow.databinding.ActivityAuthBinding;
import ca.translucide.veggiegrow.ui.MainActivity;

/**
 * Launcher screen that gates the app behind authentication. Signs the user in (FirebaseUI: email or
 * Google), then resolves their account via {@code GET /v1/me}:
 * <ul>
 *   <li>already a member → enable sync and open the app;</li>
 *   <li>invited → the server auto-joins them on /me, so they fall through to "member";</li>
 *   <li>brand new → onboarding to create their own library (they become its owner).</li>
 * </ul>
 */
public class AuthActivity extends AppCompatActivity {

    private ActivityAuthBinding binding;
    private AccountManager account;

    private final ActivityResultLauncher<Intent> signInLauncher =
            registerForActivityResult(new FirebaseAuthUIActivityResultContract(), this::onSignInResult);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        account = new AccountManager(this);

        binding.btnCreate.setOnClickListener(v -> createAccount());
        binding.btnCheckInvite.setOnClickListener(v -> resolveAccount());
        binding.btnRetry.setOnClickListener(v -> route());
    }

    @Override
    protected void onStart() {
        super.onStart();
        route();
    }

    /** Entry point: sign in if needed, otherwise resolve the account. */
    private void route() {
        if (!account.isSignedIn()) {
            launchSignIn();
        } else {
            resolveAccount();
        }
    }

    private void launchSignIn() {
        showLoading();
        List<AuthUI.IdpConfig> providers = Arrays.asList(
                new AuthUI.IdpConfig.EmailBuilder().build(),
                new AuthUI.IdpConfig.GoogleBuilder().build());
        Intent intent = AuthUI.getInstance()
                .createSignInIntentBuilder()
                .setAvailableProviders(providers)
                .setIsSmartLockEnabled(false)
                .build();
        signInLauncher.launch(intent);
    }

    private void onSignInResult(FirebaseAuthUIAuthenticationResult result) {
        if (result.getResultCode() == RESULT_OK) {
            resolveAccount();
        } else {
            // Cancelled or failed — let the user try again.
            showError(getString(R.string.sign_in_failed));
        }
    }

    /** GET /v1/me to decide between "open the app" and "onboard". */
    private void resolveAccount() {
        showLoading();
        account.fetchMe((info, error) -> {
            if (binding == null) return;
            if (error != null || info == null) {
                showError(getString(R.string.auth_error));
                return;
            }
            if (info.hasAccount()) {
                openApp();
            } else {
                showOnboarding();
            }
        });
    }

    private void createAccount() {
        String name = binding.inputLibraryName.getText() == null
                ? "" : binding.inputLibraryName.getText().toString().trim();
        showLoading();
        account.createAccount(name, (info, error) -> {
            if (binding == null) return;
            if (error != null || info == null || !info.hasAccount()) {
                Toast.makeText(this, getString(R.string.auth_error), Toast.LENGTH_LONG).show();
                showOnboarding();
                return;
            }
            openApp();
        });
    }

    private void openApp() {
        VeggieGrowApp app = (VeggieGrowApp) getApplication();
        CloudSync sync = app.cloudSync();
        if (sync != null) sync.setEnabled(true);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // --- view states -----------------------------------------------------------------------------

    private void showLoading() {
        binding.progress.setVisibility(View.VISIBLE);
        binding.onboardingGroup.setVisibility(View.GONE);
        binding.errorGroup.setVisibility(View.GONE);
    }

    private void showOnboarding() {
        binding.progress.setVisibility(View.GONE);
        binding.onboardingGroup.setVisibility(View.VISIBLE);
        binding.errorGroup.setVisibility(View.GONE);
    }

    private void showError(String message) {
        binding.progress.setVisibility(View.GONE);
        binding.onboardingGroup.setVisibility(View.GONE);
        binding.errorGroup.setVisibility(View.VISIBLE);
        binding.textError.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
