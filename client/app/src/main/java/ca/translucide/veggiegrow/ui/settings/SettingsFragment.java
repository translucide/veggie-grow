package ca.translucide.veggiegrow.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.VeggieGrowApp;
import ca.translucide.veggiegrow.data.AccountManager;
import ca.translucide.veggiegrow.data.CloudSync;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.data.model.Settings.PumpRateUnit;
import ca.translucide.veggiegrow.databinding.FragmentSettingsBinding;
import ca.translucide.veggiegrow.logic.Units;
import ca.translucide.veggiegrow.ui.auth.AuthActivity;
import ca.translucide.veggiegrow.ui.auth.MembersActivity;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private AccountManager account;

    /** The unit the pump-rate / min-water fields are currently displayed in. */
    private PumpRateUnit currentUnit = PumpRateUnit.LPH;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        String[] units = {PumpRateUnit.LPH.name(), PumpRateUnit.GPH.name()};
        binding.inputUnit.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, units));
        binding.inputUnit.setOnItemClickListener((p, v, pos, id) -> onUnitSelected());

        populateFromSettings(DataRepository.get().settings());

        binding.btnSave.setOnClickListener(v -> saveSettings());

        account = new AccountManager(requireContext());
        setupAccountSection();
    }

    private void setupAccountSection() {
        String email = account.currentEmail();
        binding.textAccountEmail.setText(email == null ? "" : getString(R.string.signed_in_as, email));
        binding.btnManageMembers.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), MembersActivity.class)));
        binding.btnSignOut.setOnClickListener(v -> signOut());

        // "Manage members" is owner-only; resolve the role, then reveal it if we're the owner.
        account.fetchMe((info, error) -> {
            if (binding == null) return;
            if (info != null && info.isOwner()) {
                binding.btnManageMembers.setVisibility(View.VISIBLE);
            }
        });
    }

    private void signOut() {
        VeggieGrowApp app = (VeggieGrowApp) requireActivity().getApplication();
        CloudSync sync = app.cloudSync();
        if (sync != null) sync.setEnabled(false);
        account.signOut(requireContext(), () -> {
            Intent intent = new Intent(requireContext(), AuthActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        });
    }

    private void populateFromSettings(Settings s) {
        currentUnit = s.pumpRateUnit;
        binding.inputUnit.setText(currentUnit.name(), false);
        binding.inputPumpRate.setText(Units.num(Units.rateToDisplay(s.pumpRate, currentUnit)));
        binding.inputMinWater.setText(Units.num(Units.volumeToDisplay(s.minWaterLevel, currentUnit)));
        binding.inputHarvestAlert.setText(String.valueOf(s.harvestAlertDays));
        binding.switchMock.setChecked(s.useMockHardware);
        updateUnitLabels();
    }

    /** When the unit changes, re-express the on-screen values without changing what's stored. */
    private void onUnitSelected() {
        PumpRateUnit selected;
        try {
            selected = PumpRateUnit.valueOf(binding.inputUnit.getText().toString());
        } catch (Exception e) {
            return;
        }
        if (selected == currentUnit) return;

        double pumpCanonical = Units.rateFromDisplay(parseDouble(text(binding.inputPumpRate), 0), currentUnit);
        double minCanonical = Units.volumeFromDisplay(parseDouble(text(binding.inputMinWater), 0), currentUnit);

        currentUnit = selected;
        binding.inputPumpRate.setText(Units.num(Units.rateToDisplay(pumpCanonical, currentUnit)));
        binding.inputMinWater.setText(Units.num(Units.volumeToDisplay(minCanonical, currentUnit)));
        updateUnitLabels();
    }

    private void updateUnitLabels() {
        binding.layoutPumpRate.setSuffixText(Units.rateUnitLabel(currentUnit));
        binding.layoutMinWater.setSuffixText(Units.volumeUnitLabel(currentUnit));
    }

    private void saveSettings() {
        Settings s = DataRepository.get().settings();
        s.pumpRateUnit = currentUnit;
        s.pumpRate = Units.rateFromDisplay(parseDouble(text(binding.inputPumpRate), 0), currentUnit);
        s.minWaterLevel = Units.volumeFromDisplay(parseDouble(text(binding.inputMinWater), 0), currentUnit);
        s.harvestAlertDays = (int) parseDouble(text(binding.inputHarvestAlert), 3);
        s.useMockHardware = binding.switchMock.isChecked();
        DataRepository.get().updateSettings();
        toast("Saved");
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
