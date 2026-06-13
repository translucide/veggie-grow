package ca.translucide.veggiegrow.ui.upload;

import android.Manifest;
import android.os.Build;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ca.translucide.veggiegrow.R;
import ca.translucide.veggiegrow.data.DataRepository;
import ca.translucide.veggiegrow.databinding.FragmentUploadBinding;
import ca.translucide.veggiegrow.hardware.HardwareController;
import ca.translucide.veggiegrow.hardware.HardwareGateway;
import ca.translucide.veggiegrow.logic.CommandBuilder;
import ca.translucide.veggiegrow.util.DateUtils;

public class UploadFragment extends Fragment {

    private FragmentUploadBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private HardwareGateway gateway;
    private final List<HardwareGateway.Device> devices = new ArrayList<>();
    private HardwareGateway.Device selectedDevice;

    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        refreshDevices();
                    } else {
                        toast(getString(R.string.bt_permission_needed));
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentUploadBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.btnConnect.setOnClickListener(v -> connect());
        binding.btnDisconnect.setOnClickListener(v -> disconnect());
        binding.btnUpload.setOnClickListener(v -> upload());
        binding.deviceDropdown.setOnItemClickListener((parent, v, position, id) -> {
            if (position >= 0 && position < devices.size()) {
                selectedDevice = devices.get(position);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean useMock = DataRepository.get().settings().useMockHardware;
        gateway = HardwareController.get(requireContext()).gateway(useMock);
        binding.modeText.setText(useMock ? R.string.using_mock : R.string.using_bluetooth);
        updateStatus();
        updatePayloadPreview();
        ensurePermissionThenRefresh();
    }

    private void ensurePermissionThenRefresh() {
        boolean useMock = DataRepository.get().settings().useMockHardware;
        if (!useMock && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        refreshDevices();
    }

    private void refreshDevices() {
        if (gateway == null || binding == null) return;
        devices.clear();
        devices.addAll(gateway.availableDevices());
        List<String> labels = new ArrayList<>();
        for (HardwareGateway.Device d : devices) labels.add(d.toString());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, labels);
        binding.deviceDropdown.setAdapter(adapter);
        if (selectedDevice == null && !devices.isEmpty()) {
            selectedDevice = devices.get(0);
            binding.deviceDropdown.setText(selectedDevice.toString(), false);
        }
    }

    private void connect() {
        if (selectedDevice == null) {
            toast(getString(R.string.select_device));
            return;
        }
        final HardwareGateway.Device device = selectedDevice;
        executor.execute(() -> {
            try {
                gateway.connect(device);
                postStatus();
            } catch (Exception e) {
                postError("Connect failed: " + e.getMessage());
            }
        });
    }

    private void disconnect() {
        executor.execute(() -> {
            gateway.disconnect();
            postStatus();
        });
    }

    private void upload() {
        final String payload = CommandBuilder.buildPayload(
                DataRepository.get().data(), DateUtils.todayMillis());
        executor.execute(() -> {
            try {
                if (!gateway.isConnected()) {
                    postError("Not connected");
                    return;
                }
                gateway.upload(payload);
                runOnUi(() -> toast(getString(R.string.upload_success, payload)));
            } catch (Exception e) {
                postError("Upload failed: " + e.getMessage());
            }
        });
    }

    private void updatePayloadPreview() {
        String payload = CommandBuilder.buildPayload(
                DataRepository.get().data(), DateUtils.todayMillis());
        binding.payloadText.setText(payload.isEmpty() ? "(no active watering rates)" : payload);
    }

    private void updateStatus() {
        if (binding == null || gateway == null) return;
        if (gateway.isConnected() && selectedDevice != null) {
            binding.statusText.setText(getString(R.string.status_connected, selectedDevice.toString()));
        } else {
            binding.statusText.setText(R.string.status_disconnected);
        }
    }

    private void postStatus() {
        runOnUi(this::updateStatus);
    }

    private void postError(String msg) {
        runOnUi(() -> {
            updateStatus();
            toast(msg);
        });
    }

    private void runOnUi(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(() -> {
            if (binding != null) r.run();
        });
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
