# VeggieGrow

Android app (Java) to organize, monitor and control veggie growth across **Growth Spaces**,
each holding multiple **Bins**. It stores configuration, computes harvest/reservoir alerts, and
uploads per-bin watering levels to an Arduino over **Bluetooth Classic serial (SPP/RFCOMM)**.

## Repository layout

- `client/` — the Android app (this Gradle project).
- `server/` — the Go REST API the app syncs against (see [server/README.md](server/README.md)).

## Build & run

The Android client is a standard Android Studio / Gradle project (`minSdk 24`, `targetSdk 34`)
under `client/`.

- **Android Studio**: *Open* the `client/` folder and Run on a device/emulator. Studio supplies the
  Android SDK and resolves dependencies automatically.
- **Command line** (requires the Android SDK; set `ANDROID_HOME` or `local.properties` →
  `sdk.dir=...`):
  ```
  cd client
  ./gradlew assembleDebug      # build the APK
  ./gradlew test               # run the JVM unit tests
  ```

The Gradle wrapper (`gradlew`) is included under `client/`.

## Architecture

```
ca.translucide.veggiegrow
├── data/            model POJOs + DataRepository (single source of truth) + JsonStore + import/export
├── logic/           GrowthCalculator, AlertEngine, CommandBuilder  (pure Java, unit-tested)
├── hardware/        HardwareGateway (interface), MockHardwareGateway, BluetoothSerialGateway
├── ui/              single-activity + Navigation: spaces / bin form / presets / alerts / upload / settings
└── work/            AlertWorker (periodic notifications)
```

- **Persistence**: the whole model is one JSON file (`filesDir/veggiegrow.json`) via Gson.
  Export/Import (Settings screen) reads/writes the identical JSON via the Storage Access Framework.
  Images are embedded as base64 so an export is fully self-contained.
- **Theme**: dark, green-accented. The palette lives in `res/values/colors.xml` — change it there to
  re-theme the whole app. Sans-serif (Roboto) typography only. Leaf logo in `res/drawable/ic_leaf.xml`.

## Key behaviours

- **Watering rate** is time-varying: each bin has a schedule of `(dayOffset → rate)` points; the
  active rate is the latest point whose day has been reached.
- **Harvest**: per-bin *first harvest days from start* + *harvest every X days* (0 = once).
  An alert fires when the next harvest is within the global *harvest alert days*.
- **Reservoir** (manual refill tracking): tap **Mark refilled** to reset. Estimated remaining =
  `reservoirSize − (Σ current watering rates) × daysSinceRefill`; alerts at the global *min water level*.
  The formula lives in one method: `GrowthCalculator.estimatedReservoirRemaining`.

## Controller (Arduino) protocol

On **Upload**, the app sends a newline-terminated line of current per-bin watering levels:

```
A1:0.09,A2:0.08,B1:0.10\n
```

`<SpaceCode><BinCode>:<rate>` pairs, comma-separated; bins with a zero current rate are omitted.
These persist on the controller until the next upload. The wire format is defined in
`logic/CommandBuilder` (SPP UUID `00001101-0000-1000-8000-00805F9B34FB` in `BluetoothSerialGateway`).

A **mock hardware** mode (Settings → *Use mock hardware*, on by default) lets the whole upload flow
be exercised without a physical Arduino.

## Tests

`app/src/test/java/.../logic/` covers `GrowthCalculator` (rate selection, harvest scheduling,
reservoir depletion) and `CommandBuilder` (payload format). Run with `./gradlew test`.
