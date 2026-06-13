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
import ca.translucide.veggiegrow.data.model.GrowthSpace;
import ca.translucide.veggiegrow.databinding.FragmentSpacesBinding;
import ca.translucide.veggiegrow.util.DateUtils;

public class SpacesFragment extends Fragment {

    private FragmentSpacesBinding binding;
    private SpacesAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSpacesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new SpacesAdapter(new SpacesAdapter.Listener() {
            @Override
            public void onClick(GrowthSpace space) {
                Bundle args = new Bundle();
                args.putString("spaceCode", space.code);
                NavHostFragment.findNavController(SpacesFragment.this)
                        .navigate(R.id.spaceDetailFragment, args);
            }

            @Override
            public void onLongClick(GrowthSpace space) {
                showSpaceOptions(space);
            }
        });
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter);

        binding.fabAdd.setOnClickListener(v ->
                SpaceFormDialog.newInstance(null).show(getChildFragmentManager(), "space_form"));

        DataRepository.get().liveData().observe(getViewLifecycleOwner(), data -> {
            adapter.submit(data.spaces, data.settings, DateUtils.todayMillis());
            binding.empty.setVisibility(data.spaces.isEmpty() ? View.VISIBLE : View.GONE);
        });

        setupMenu();
    }

    private void setupMenu() {
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.spaces_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.action_presets) {
                    NavHostFragment.findNavController(SpacesFragment.this)
                            .navigate(R.id.presetsFragment);
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void showSpaceOptions(GrowthSpace space) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(space.code)
                .setItems(new CharSequence[]{getString(R.string.action_edit), getString(R.string.action_delete)},
                        (d, which) -> {
                            if (which == 0) {
                                SpaceFormDialog.newInstance(space.code)
                                        .show(getChildFragmentManager(), "space_form");
                            } else {
                                confirmDelete(space);
                            }
                        })
                .show();
    }

    private void confirmDelete(GrowthSpace space) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.action_delete) + " " + space.code + "?")
                .setMessage(space.bins.size() + " bin(s) will also be removed.")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> DataRepository.get().removeSpace(space))
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
