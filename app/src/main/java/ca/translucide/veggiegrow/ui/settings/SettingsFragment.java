package ca.translucide.veggiegrow.ui.settings;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.data.ImportExportManager;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.data.model.Settings.PumpRateUnit;
import ca.translucide.veggiegrow.databinding.FragmentSettingsBinding;
import ca.translucide.veggiegrow.logic.Units;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private ImportExportManager importExport;

    /** The unit the pump-rate / min-water fields are currently displayed in. */
    private PumpRateUnit currentUnit = PumpRateUnit.LPH;

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importExport = new ImportExportManager(DataRepository.get());

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(ImportExportManager.MIME_TYPE),
                this::onExportUri);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onImportUri);
    }

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
        binding.btnExport.setOnClickListener(v ->
                exportLauncher.launch(ImportExportManager.SUGGESTED_FILENAME));
        binding.btnImport.setOnClickListener(v ->
                importLauncher.launch(new String[]{ImportExportManager.MIME_TYPE, "text/*", "*/*"}));
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
        DataRepository.get().commit();
        toast("Saved");
    }

    private void onExportUri(@Nullable Uri uri) {
        if (uri == null) return;
        try {
            importExport.exportTo(requireContext().getContentResolver(), uri);
            toast(getString(R.string.export_done));
        } catch (Exception e) {
            toast(getString(R.string.import_failed, e.getMessage()));
        }
    }

    private void onImportUri(@Nullable Uri uri) {
        if (uri == null) return;
        try {
            importExport.importFrom(requireContext().getContentResolver(), uri);
            toast(getString(R.string.import_done));
            populateFromSettings(DataRepository.get().settings());
        } catch (Exception e) {
            toast(getString(R.string.import_failed, e.getMessage()));
        }
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
