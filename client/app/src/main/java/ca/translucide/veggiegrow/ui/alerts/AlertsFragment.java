package ca.translucide.veggiegrow.ui.alerts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.databinding.FragmentAlertsBinding;
import ca.translucide.veggiegrow.logic.Alert;
import ca.translucide.veggiegrow.logic.AlertEngine;
import ca.translucide.veggiegrow.util.DateUtils;

public class AlertsFragment extends Fragment {

    private FragmentAlertsBinding binding;
    private AlertAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAlertsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new AlertAdapter();
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter);

        DataRepository.get().liveData().observe(getViewLifecycleOwner(), data -> {
            List<Alert> alerts = AlertEngine.evaluate(data, DateUtils.todayMillis());
            adapter.submit(alerts);
            binding.empty.setVisibility(alerts.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
