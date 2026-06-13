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
import ca.translucide.veggiegrow.databinding.FragmentSettingsBinding;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private ImportExportManager importExport;

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
        Settings s = DataRepository.get().settings();

        binding.inputPumpRate.setText(String.valueOf(s.pumpRate));
        binding.inputMinWater.setText(String.valueOf(s.minWaterLevel));
        binding.inputHarvestAlert.setText(String.valueOf(s.harvestAlertDays));
        binding.switchMock.setChecked(s.useMockHardware);

        String[] units = {Settings.PumpRateUnit.LPM.name(), Settings.PumpRateUnit.GPM.name()};
        binding.inputUnit.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, units));
        binding.inputUnit.setText(s.pumpRateUnit.name(), false);

        binding.btnSave.setOnClickListener(v -> saveSettings());
        binding.btnExport.setOnClickListener(v ->
                exportLauncher.launch(ImportExportManager.SUGGESTED_FILENAME));
        binding.btnImport.setOnClickListener(v ->
                importLauncher.launch(new String[]{ImportExportManager.MIME_TYPE, "text/*", "*/*"}));
    }

    private void saveSettings() {
        Settings s = DataRepository.get().settings();
        s.pumpRate = parseDouble(text(binding.inputPumpRate), 1.0);
        s.minWaterLevel = parseDouble(text(binding.inputMinWater), 1.0);
        s.harvestAlertDays = (int) parseDouble(text(binding.inputHarvestAlert), 3);
        s.useMockHardware = binding.switchMock.isChecked();
        try {
            s.pumpRateUnit = Settings.PumpRateUnit.valueOf(binding.inputUnit.getText().toString());
        } catch (Exception ignored) {
            s.pumpRateUnit = Settings.PumpRateUnit.LPM;
        }
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
            // Refresh the form fields from the freshly-imported settings.
            onViewCreated(requireView(), null);
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
