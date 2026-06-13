package ca.translucide.veggiegrow.hardware;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Real Bluetooth Classic (SPP / RFCOMM) link to the Arduino pump controller. Connects to a paired
 * device, then writes the watering-command line to its output stream.
 *
 * <p>The caller must ensure the relevant runtime permissions are granted (BLUETOOTH_CONNECT on
 * API 31+) and must invoke {@link #connect}/{@link #upload} off the main thread.
 */
public class BluetoothSerialGateway implements HardwareGateway {

    private static final String TAG = "BtSerialGateway";

    /** Standard Serial Port Profile UUID (used by HC-05/HC-06 style modules). */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context appContext;
    private final BluetoothAdapter adapter;

    private BluetoothSocket socket;
    private OutputStream outputStream;

    public BluetoothSerialGateway(Context context) {
        this.appContext = context.getApplicationContext();
        BluetoothAdapter a;
        try {
            android.bluetooth.BluetoothManager manager =
                    (android.bluetooth.BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
            a = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        } catch (Exception e) {
            a = BluetoothAdapter.getDefaultAdapter();
        }
        this.adapter = a;
    }

    public boolean isBluetoothAvailable() {
        return adapter != null;
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // legacy permissions are install-time
    }

    @SuppressLint("MissingPermission") // guarded by hasConnectPermission()
    @Override
    public List<Device> availableDevices() {
        List<Device> result = new ArrayList<>();
        if (adapter == null || !hasConnectPermission()) {
            return result;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                result.add(new Device(d.getName(), d.getAddress()));
            }
        }
        return result;
    }

    @SuppressLint("MissingPermission") // guarded by hasConnectPermission()
    @Override
    public void connect(Device device) throws Exception {
        if (adapter == null) throw new IOException("Bluetooth not available on this device");
        if (!hasConnectPermission()) throw new SecurityException("BLUETOOTH_CONNECT permission not granted");

        disconnect();

        BluetoothDevice btDevice = adapter.getRemoteDevice(device.address);
        // Cancel discovery first; it slows down / can break a connection attempt.
        try {
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }

        BluetoothSocket s = btDevice.createRfcommSocketToServiceRecord(SPP_UUID);
        s.connect(); // blocking
        this.socket = s;
        this.outputStream = s.getOutputStream();
        Log.i(TAG, "Connected to " + device);
    }

    @Override
    public void disconnect() {
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        outputStream = null;
        socket = null;
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    @Override
    public void upload(String payload) throws Exception {
        if (outputStream == null) throw new IOException("Not connected");
        // Newline-terminated so the Arduino can readStringUntil('\n').
        byte[] bytes = (payload + "\n").getBytes(StandardCharsets.US_ASCII);
        outputStream.write(bytes);
        outputStream.flush();
        Log.i(TAG, "Uploaded: " + payload);
    }
}
