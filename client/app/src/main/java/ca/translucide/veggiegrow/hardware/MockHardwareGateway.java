package ca.translucide.veggiegrow.hardware;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-memory simulator used when {@code settings.useMockHardware} is true. Lets the full upload
 * flow be exercised without a physical Arduino. Records the last uploaded payload.
 */
public class MockHardwareGateway implements HardwareGateway {

    private static final String TAG = "MockHardware";

    private boolean connected;
    private Device connectedDevice;
    private String lastPayload;

    @Override
    public List<Device> availableDevices() {
        return new ArrayList<>(Arrays.asList(
                new Device("Mock Arduino A", "00:11:22:33:44:55"),
                new Device("Mock Arduino B", "00:11:22:33:44:66")));
    }

    @Override
    public void connect(Device device) {
        this.connectedDevice = device;
        this.connected = true;
        Log.i(TAG, "Connected to mock device " + device);
    }

    @Override
    public void disconnect() {
        this.connected = false;
        this.connectedDevice = null;
        Log.i(TAG, "Disconnected mock device");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void upload(String payload) {
        this.lastPayload = payload;
        Log.i(TAG, "Mock upload to " + connectedDevice + ": " + payload);
    }

    /** Last payload passed to {@link #upload(String)}, for verification/UI feedback. */
    public String getLastPayload() {
        return lastPayload;
    }
}
