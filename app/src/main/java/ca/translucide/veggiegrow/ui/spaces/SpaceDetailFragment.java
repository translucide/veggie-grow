package ca.translucide.veggiegrow.ui.spaces;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.data.model.Bin;
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.data.model.Settings;
import ca.translucide.veggiegrow.databinding.FragmentSpaceDetailBinding;
import ca.translucide.veggiegrow.logic.GrowthCalculator;
import ca.translucide.veggiegrow.logic.Units;
import ca.translucide.veggiegrow.util.DateUtils;

public class SpaceDetailFragment extends Fragment {

    private FragmentSpaceDetailBinding binding;
    private BinAdapter adapter;
    private String spaceCode;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSpaceDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        spaceCode = getArguments() != null ? getArguments().getString("spaceCode") : null;

        adapter = new BinAdapter(new BinAdapter.Listener() {
            @Override
            public void onClick(Bin bin) {
                openBinForm(bin.code);
            }

            @Override
            public void onLongClick(Bin bin) {
                confirmDeleteBin(bin);
            }
        });
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter);

        binding.fabAdd.setOnClickListener(v -> openBinForm(null));
        binding.btnRefilled.setOnClickListener(v -> {
            GrowthSpace s = currentSpace();
            if (s != null) DataRepository.get().markRefilled(s, DateUtils.todayMillis());
        });

        DataRepository.get().liveData().observe(getViewLifecycleOwner(), data -> render());
        setupMenu();
    }

    private void render() {
        GrowthSpace s = currentSpace();
        if (s == null) {
            // Space was deleted; pop back.
            NavHostFragment.findNavController(this).popBackStack();
            return;
        }
        long now = DateUtils.todayMillis();
        Settings.PumpRateUnit unit = DataRepository.get().settings().pumpRateUnit;
        double remaining = GrowthCalculator.estimatedReservoirRemaining(s, now);
        binding.reservoirText.setText(getString(R.string.reservoir_remaining,
                Units.num(Units.volumeToDisplay(remaining, unit)),
                Units.formatVolume(s.waterReservoirSize, unit)));
        binding.lastRefillText.setText(getString(R.string.last_refill, DateUtils.format(s.lastRefillEpochMillis)));

        adapter.submit(s.code, s.bins, DataRepository.get().settings(), now);
        binding.empty.setVisibility(s.bins.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openBinForm(@Nullable String binCode) {
        Bundle args = new Bundle();
        args.putString("spaceCode", spaceCode);
        if (binCode != null) args.putString("binCode", binCode);
        NavHostFragment.findNavController(this).navigate(R.id.binFormFragment, args);
    }

    private void confirmDeleteBin(Bin bin) {
        GrowthSpace s = currentSpace();
        if (s == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.action_delete) + " " + s.code + bin.code + "?")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> DataRepository.get().removeBin(s, bin))
                .show();
    }

    private void setupMenu() {
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.space_detail_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.action_edit_space) {
                    SpaceFormDialog.newInstance(spaceCode).show(getChildFragmentManager(), "space_form");
                    return true;
                } else if (id == R.id.action_delete_space) {
                    GrowthSpace s = currentSpace();
                    if (s != null) {
                        DataRepository.get().removeSpace(s);
                        NavHostFragment.findNavController(SpaceDetailFragment.this).popBackStack();
                    }
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    @Nullable
    private GrowthSpace currentSpace() {
        return DataRepository.get().data().findSpace(spaceCode);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
