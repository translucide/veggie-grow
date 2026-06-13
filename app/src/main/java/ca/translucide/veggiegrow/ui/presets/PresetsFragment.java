package ca.translucide.veggiegrow.ui.presets;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.data.model.Preset;
import ca.translucide.veggiegrow.databinding.FragmentPresetsBinding;

public class PresetsFragment extends Fragment {

    private FragmentPresetsBinding binding;
    private PresetAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPresetsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new PresetAdapter(this::confirmDelete);
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter);

        DataRepository.get().liveData().observe(getViewLifecycleOwner(), data -> {
            adapter.submit(data.presets);
            binding.empty.setVisibility(data.presets.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void confirmDelete(Preset preset) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.action_delete) + " \"" + preset.name + "\"?")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> DataRepository.get().removePreset(preset))
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
