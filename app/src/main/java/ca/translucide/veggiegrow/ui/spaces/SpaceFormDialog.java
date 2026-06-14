package ca.translucide.veggiegrow.ui.spaces;

import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.data.model.AppData;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.databinding.DialogSpaceFormBinding;
import ca.translucide.veggiegrow.logic.Units;
import ca.translucide.veggiegrow.util.ImageUtils;

/**
 * Add or edit a {@link GrowthSpace}. Pass an existing space code via {@code newInstance} to edit.
 */
public class SpaceFormDialog extends DialogFragment {

    private static final String ARG_CODE = "spaceCode";

    private DialogSpaceFormBinding binding;
    private String imageBase64;
    private GrowthSpace editing;
    private Settings.PumpRateUnit unit = Settings.PumpRateUnit.LPH;

    private ActivityResultLauncher<String> imagePicker;

    public static SpaceFormDialog newInstance(@Nullable String existingCode) {
        SpaceFormDialog d = new SpaceFormDialog();
        Bundle b = new Bundle();
        if (existingCode != null) b.putString(ARG_CODE, existingCode);
        d.setArguments(b);
        return d;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogSpaceFormBinding.inflate(getLayoutInflater());

        unit = DataRepository.get().settings().pumpRateUnit;
        binding.layoutReservoir.setSuffixText(Units.volumeUnitLabel(unit));

        String code = getArguments() != null ? getArguments().getString(ARG_CODE) : null;
        if (code != null) {
            editing = DataRepository.get().data().findSpace(code);
        }
        if (editing != null) {
            binding.inputCode.setText(editing.code);
            binding.inputName.setText(editing.name);
            binding.inputReservoir.setText(Units.num(
                    Units.volumeToDisplay(editing.waterReservoirSize, unit)));
            imageBase64 = editing.imageBase64;
            android.graphics.Bitmap bmp = ImageUtils.base64ToBitmap(imageBase64);
            if (bmp != null) binding.imagePreview.setImageBitmap(bmp);
        }

        binding.btnPickImage.setOnClickListener(v -> imagePicker.launch("image/*"));

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(editing != null ? R.string.edit_space : R.string.add_space)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null) // overridden below to validate
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog) getDialog();
        if (dialog != null) {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    .setOnClickListener(v -> save());
        }
    }

    private void onImagePicked(@Nullable Uri uri) {
        if (uri == null) return;
        try {
            imageBase64 = ImageUtils.uriToBase64(requireContext(), uri);
            android.graphics.Bitmap bmp = ImageUtils.base64ToBitmap(imageBase64);
            if (bmp != null) binding.imagePreview.setImageBitmap(bmp);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Image error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void save() {
        String code = text(binding.inputCode).toUpperCase();
        if (TextUtils.isEmpty(code)) {
            binding.inputCode.setError("Required");
            return;
        }
        AppData data = DataRepository.get().data();
        GrowthSpace existing = data.findSpace(code);
        if (existing != null && existing != editing) {
            binding.inputCode.setError("Code already in use");
            return;
        }

        // Reservoir size is entered in the display unit; store canonically in mL.
        double reservoir = Units.volumeFromDisplay(parseDouble(text(binding.inputReservoir)), unit);

        if (editing != null) {
            editing.code = code;
            editing.name = text(binding.inputName);
            editing.waterReservoirSize = reservoir;
            editing.imageBase64 = imageBase64;
            DataRepository.get().commit();
        } else {
            GrowthSpace s = new GrowthSpace();
            s.code = code;
            s.name = text(binding.inputName);
            s.waterReservoirSize = reservoir;
            s.imageBase64 = imageBase64;
            DataRepository.get().addSpace(s);
        }
        dismiss();
    }

    private String text(com.google.android.material.textfield.TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
