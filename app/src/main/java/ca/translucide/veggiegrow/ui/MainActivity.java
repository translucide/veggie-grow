package ca.translucide.veggiegrow.ui;

import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.HashSet;
import java.util.Set;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.databinding.ActivityMainBinding;

/**
 * Single activity hosting the navigation graph and the bottom navigation bar.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = host.getNavController();

        // Top-level destinations (no Up arrow, shown in bottom nav).
        Set<Integer> topLevel = new HashSet<>();
        topLevel.add(R.id.spacesFragment);
        topLevel.add(R.id.alertsFragment);
        topLevel.add(R.id.uploadFragment);
        topLevel.add(R.id.settingsFragment);
        appBarConfiguration = new AppBarConfiguration.Builder(topLevel).build();

        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        BottomNavigationView bottomNav = binding.bottomNav;
        NavigationUI.setupWithNavController(bottomNav, navController);

        maybeRequestNotificationPermission();
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            }).launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
    }
}
