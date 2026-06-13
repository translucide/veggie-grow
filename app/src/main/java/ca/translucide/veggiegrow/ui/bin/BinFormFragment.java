package ca.translucide.veggiegrow.ui.bin;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.data.model.WateringRatePoint;
import ca.translucide.veggiegrow.databinding.FragmentBinFormBinding;
import ca.translucide.veggiegrow.databinding.ItemRatePointBinding;
import ca.translucide.veggiegrow.util.DateUtils;
import ca.translucide.veggiegrow.util.ImageUtils;

/**
 * Add / edit a bin, including its time-varying watering schedule. Supports prefilling from a preset
 * and saving the current form back as a preset.
 */
public class BinFormFragment extends Fragment {

    private FragmentBinFormBinding binding;
    private final List<ItemRatePointBinding> rateRows = new ArrayList<>();

    private String spaceCode;
    private String binCode; // null when adding
    private GrowthSpace space;
    private Bin editing;     // null when adding

    private String imageBase64;
    private long startDateMillis;

    private ActivityResultLauncher<String> imagePicker;
    private final ExecutorService imageDownloadExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBinFormBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        spaceCode = args != null ? args.getString("spaceCode") : null;
        binCode = args != null ? args.getString("binCode") : null;
        space = DataRepository.get().data().findSpace(spaceCode);

        startDateMillis = DateUtils.todayMillis();

        if (space != null && binCode != null) {
            for (Bin b : space.bins) {
                if (binCode.equalsIgnoreCase(b.code)) {
                    editing = b;
                    break;
                }
            }
        }
        if (editing != null) {
            prefillFrom(editing);
        } else {
            addRateRow(0, 0d);
            updateStartDateLabel();
        }

        binding.btnPickImage.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnFindPhotos.setOnClickListener(v -> findPhotos());
        getChildFragmentManager().setFragmentResultListener(
                PhotoPickerDialog.RESULT_KEY, getViewLifecycleOwner(),
                (key, bundle) -> applyRemotePhoto(bundle.getString(PhotoPickerDialog.RESULT_URL)));
        binding.inputStartDate.setOnClickListener(v -> pickDate());
        binding.btnAddRate.setOnClickListener(v -> addRateRow(0, 0d));
        binding.btnApplyPreset.setOnClickListener(v -> showPresetPicker());
        binding.btnSavePreset.setOnClickListener(v -> saveAsPreset());
        binding.btnSave.setOnClickListener(v -> save());
    }

    private void prefillFrom(Bin bin) {
        binding.inputCode.setText(bin.code);
        binding.inputVariety.setText(bin.varietyName);
        binding.inputFirstHarvest.setText(String.valueOf(bin.firstHarvestDays));
        binding.inputHarvestInterval.setText(String.valueOf(bin.harvestIntervalDays));
        imageBase64 = bin.imageBase64;
        android.graphics.Bitmap bmp = ImageUtils.base64ToBitmap(imageBase64);
        if (bmp != null) binding.imagePreview.setImageBitmap(bmp);
        startDateMillis = bin.startDateEpochMillis > 0 ? bin.startDateEpochMillis : DateUtils.todayMillis();
        updateStartDateLabel();
        rebuildRateRows(bin.wateringSchedule);
    }

    private void rebuildRateRows(List<WateringRatePoint> points) {
        binding.rateContainer.removeAllViews();
        rateRows.clear();
        if (points == null || points.isEmpty()) {
            addRateRow(0, 0d);
            return;
        }
        for (WateringRatePoint p : points) {
            addRateRow(p.dayOffset, p.rate);
        }
    }

    private void addRateRow(int day, double rate) {
        ItemRatePointBinding row = ItemRatePointBinding.inflate(
                getLayoutInflater(), binding.rateContainer, false);
        row.inputDay.setText(String.valueOf(day));
        row.inputRate.setText(String.valueOf(rate));
        row.btnRemove.setOnClickListener(v -> {
            binding.rateContainer.removeView(row.getRoot());
            rateRows.remove(row);
        });
        rateRows.add(row);
        binding.rateContainer.addView(row.getRoot());
    }

    private void pickDate() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(startDateMillis)
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            startDateMillis = DateUtils.startOfDay(selection);
            updateStartDateLabel();
        });
        picker.show(getChildFragmentManager(), "date");
    }

    private void updateStartDateLabel() {
        binding.inputStartDate.setText(DateUtils.format(startDateMillis));
    }

    private void onImagePicked(@Nullable Uri uri) {
        if (uri == null) return;
        try {
            imageBase64 = ImageUtils.uriToBase64(requireContext(), uri);
            android.graphics.Bitmap bmp = ImageUtils.base64ToBitmap(imageBase64);
            if (bmp != null) binding.imagePreview.setImageBitmap(bmp);
        } catch (Exception e) {
            toast("Image error: " + e.getMessage());
        }
    }

    private void findPhotos() {
        String variety = text(binding.inputVariety);
        if (variety.isEmpty()) {
            toast(getString(R.string.enter_variety_first));
            return;
        }
        PhotoPickerDialog.newInstance(variety).show(getChildFragmentManager(), "photo_picker");
    }

    private void applyRemotePhoto(@Nullable String url) {
        if (url == null || url.isEmpty()) return;
        toast(getString(R.string.applying_photo));
        imageDownloadExecutor.execute(() -> {
            String encoded;
            try {
                encoded = ImageUtils.urlToBase64(url);
            } catch (Exception e) {
                postToUi(() -> toast("Image error: " + e.getMessage()));
                return;
            }
            postToUi(() -> {
                imageBase64 = encoded;
                android.graphics.Bitmap bmp = ImageUtils.base64ToBitmap(encoded);
                if (bmp != null) binding.imagePreview.setImageBitmap(bmp);
            });
        });
    }

    private void postToUi(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(() -> {
            if (binding != null) r.run();
        });
    }

    private void showPresetPicker() {
        List<Preset> presets = DataRepository.get().data().presets;
        if (presets.isEmpty()) {
            toast(getString(R.string.no_presets));
            return;
        }
        CharSequence[] names = new CharSequence[presets.size()];
        for (int i = 0; i < presets.size(); i++) names[i] = presets.get(i).name;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.apply_preset)
                .setItems(names, (d, which) -> applyPreset(presets.get(which)))
                .show();
    }

    private void applyPreset(Preset preset) {
        Bin temp = new Bin();
        preset.applyTo(temp);
        binding.inputVariety.setText(temp.varietyName);
        binding.inputFirstHarvest.setText(String.valueOf(temp.firstHarvestDays));
        binding.inputHarvestInterval.setText(String.valueOf(temp.harvestIntervalDays));
        imageBase64 = temp.imageBase64;
        android.graphics.Bitmap bmp = ImageUtils.base64ToBitmap(imageBase64);
        binding.imagePreview.setImageBitmap(bmp != null ? bmp : null);
        if (bmp == null) binding.imagePreview.setImageResource(R.drawable.ic_leaf);
        rebuildRateRows(temp.wateringSchedule);
    }

    private void saveAsPreset() {
        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint(R.string.preset_name);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.save_as_preset)
                .setView(nameInput)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("Name required");
                        return;
                    }
                    Bin snapshot = buildBinFromForm(false);
                    if (snapshot == null) return;
                    DataRepository.get().upsertPreset(Preset.fromBin(name, snapshot));
                    toast("Preset saved");
                })
                .show();
    }

    private void save() {
        if (space == null) {
            toast("Space not found");
            return;
        }
        Bin result = buildBinFromForm(true);
        if (result == null) return;

        if (editing != null) {
            // Mutate in place.
            editing.code = result.code;
            editing.varietyName = result.varietyName;
            editing.imageBase64 = result.imageBase64;
            editing.startDateEpochMillis = result.startDateEpochMillis;
            editing.firstHarvestDays = result.firstHarvestDays;
            editing.harvestIntervalDays = result.harvestIntervalDays;
            editing.wateringSchedule = result.wateringSchedule;
            DataRepository.get().commit();
        } else {
            DataRepository.get().addBin(space, result);
        }
        NavHostFragment.findNavController(this).popBackStack();
    }

    /**
     * Reads the form into a {@link Bin}. When {@code validateCode} is true, the bin code is
     * required and checked for uniqueness within the space.
     */
    @Nullable
    private Bin buildBinFromForm(boolean validateCode) {
        Bin bin = new Bin();
        bin.code = text(binding.inputCode).toUpperCase();
        if (validateCode) {
            if (TextUtils.isEmpty(bin.code)) {
                binding.inputCode.setError("Required");
                return null;
            }
            for (Bin b : space.bins) {
                if (b != editing && bin.code.equalsIgnoreCase(b.code)) {
                    binding.inputCode.setError("Code already used in this space");
                    return null;
                }
            }
        }
        bin.varietyName = text(binding.inputVariety);
        bin.imageBase64 = imageBase64;
        bin.startDateEpochMillis = startDateMillis;
        bin.firstHarvestDays = parseInt(text(binding.inputFirstHarvest));
        bin.harvestIntervalDays = parseInt(text(binding.inputHarvestInterval));
        bin.wateringSchedule = collectRatePoints();
        return bin;
    }

    private List<WateringRatePoint> collectRatePoints() {
        List<WateringRatePoint> points = new ArrayList<>();
        for (ItemRatePointBinding row : rateRows) {
            String dayStr = textOf(row.inputDay);
            String rateStr = textOf(row.inputRate);
            if (dayStr.isEmpty() && rateStr.isEmpty()) continue;
            points.add(new WateringRatePoint(parseInt(dayStr), parseDouble(rateStr)));
        }
        return points;
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private String textOf(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        rateRows.clear();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        imageDownloadExecutor.shutdownNow();
    }
}
