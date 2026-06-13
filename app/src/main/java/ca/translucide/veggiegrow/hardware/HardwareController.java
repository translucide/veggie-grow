package ca.translucide.veggiegrow.hardware;

import android.content.Context;

/**
 * Process-wide holder for the active {@link HardwareGateway}. Swaps between the mock and Bluetooth
 * implementations based on the current "use mock hardware" setting, preserving the connection
 * object across screens.
 */
public class HardwareController {

    private static HardwareController instance;

    private final Context appContext;
    private HardwareGateway gateway;
    private boolean mockMode;
    private boolean initialised;

    private HardwareController(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized HardwareController get(Context context) {
        if (instance == null) {
            instance = new HardwareController(context);
        }
        return instance;
    }

    /** Returns the gateway matching {@code useMock}, recreating it if the mode changed. */
    public synchronized HardwareGateway gateway(boolean useMock) {
        if (!initialised || useMock != mockMode) {
            if (gateway != null) {
                gateway.disconnect();
            }
            gateway = useMock
                    ? new MockHardwareGateway()
                    : new BluetoothSerialGateway(appContext);
            mockMode = useMock;
            initialised = true;
        }
        return gateway;
    }

    public boolean isMockMode() {
        return mockMode;
    }
}
