package ca.translucide.veggiegrow.hardware;

import java.util.List;

/**
 * Abstraction over the pump controller link. Implemented by {@link BluetoothSerialGateway} (real
 * Bluetooth SPP) and {@link MockHardwareGateway} (in-memory simulator for development/testing).
 *
 * <p>All calls that touch I/O may block and should be invoked off the main thread.
 */
public interface HardwareGateway {

    /** A controller device the user can connect to. */
    class Device {
        public final String name;
        public final String address;

        public Device(String name, String address) {
            this.name = name;
            this.address = address;
        }

        @Override
        public String toString() {
            return (name != null ? name : "(unknown)") + " [" + address + "]";
        }
    }

    /** Devices available to connect to (e.g. paired Bluetooth devices). */
    List<Device> availableDevices();

    /** Opens a connection to the given device. Blocking. */
    void connect(Device device) throws Exception;

    void disconnect();

    boolean isConnected();

    /** Sends the payload line (e.g. "A1:0.09,A2:0.08"). Blocking. */
    void upload(String payload) throws Exception;
}
