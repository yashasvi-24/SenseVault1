# SenseVault — Android Studio project

SenseVault is a local-first Android app for exploring device sensors and keeping encrypted sensor snapshots with optional private notes.

## Implemented

- **Device sensor monitor:** reads the sensor list exposed by `SensorManager.getSensorList(Sensor.TYPE_ALL)` and subscribes to continuous and on-change readings while the authenticated app is open, and arms Android trigger sensors where supported. Sensors that have not produced a reading yet are shown as waiting.
- **Sensor snapshot:** saves the latest available value for every sensor reported by Android, the sensor name/type/vendor, a timestamp, and an optional note.
- **Encrypted journal:** each entry is encrypted with AES/GCM/NoPadding using a 256-bit key held by Android Keystore. Entries are stored in the app's private files directory. The vault screen can decrypt, view, and delete entries.
- **Authorization:** first-run six-digit PIN setup, PIN login, PIN change, a 30-second pause after five incorrect attempts, explicit lock, and automatic lock when the app goes to the background. The PIN is stored as a salted PBKDF2-HMAC-SHA256 hash, not as plain text.
- **Selfie expression check:** CameraX captures a front-camera selfie and ML Kit runs face/expression classification on-device. It reports only smile/eye-expression signals. The temporary selfie is deleted after analysis and is not stored in the journal.

## Important limitations

1. Android can only expose sensors supported and permitted by the specific phone. Some devices omit sensors, delay readings, or restrict certain sensor types. The app lists all sensors Android reports and captures readings it can access; protected health sensors such as heart rate need separate platform health permission and are deliberately not requested by this starter app.
2. ML Kit's face API can provide facial-expression signals such as smiling and eye-open probability. This is **not a mood detector** and cannot reliably determine how a person feels. The UI deliberately describes it as an expression estimate, not a mental-health assessment.
3. The app currently uses local PIN authorization, not a remote account or server. It has no internet permission and does not upload snapshots or selfies. The custom PIN gate is an app-level access control; it is not a substitute for a device lock screen or a security audit.
4. AES keys are stored by Android Keystore. If app data or its Keystore key is removed, encrypted journal entries may no longer be recoverable. App backup is disabled to avoid copying private vault files outside the app.
5. There is no PIN recovery in this starter. Forgetting the PIN means the app must be reset, which also removes the local vault.
6. This source project has not been compiled or tested on a physical device in this environment because no Android SDK/device is available here. The archive includes the Gradle version properties but not the generated Gradle wrapper launcher/JAR; generate those with the command above or use your installed Gradle distribution.

## Open and run

1. Install a current Android Studio release and an Android SDK platform API 37 (the project compiles against API 37 and targets API 36), plus a JDK 17 toolchain.
2. Open the `SenseVault` folder in Android Studio.
3. If you have a local Gradle 9.6 installation, generate the standard wrapper once from the project root with `gradle wrapper --gradle-version 9.6.0`. Then open the project in Android Studio and allow Gradle to sync and download the AndroidX, CameraX, and ML Kit dependencies.
4. Connect an Android phone (recommended) or create an emulator with a front camera and sensors.
5. Run the `app` configuration. On first launch, create a six-digit PIN. Grant camera permission when opening the Selfie tab.

## Project structure

- `app/src/main/java/com/example/sensevault/MainActivity.kt` — app entry point
- `auth/PinAuth.kt` — local PIN setup and verification
- `sensors/SensorMonitor.kt` — device sensor discovery and live updates
- `data/SecureVault.kt` — AES-GCM encryption and entry persistence
- `camera/SelfieScreen.kt` — CameraX preview/capture and ML Kit expression signal
- `ui/AppUi.kt` — authorization, sensor, vault, selfie, and settings screens

## Build configuration

The project uses Android Gradle Plugin 9.4.0, Gradle 9.6.0, Kotlin/Compose compiler plugin 2.2.10, Compose BOM 2026.09.00, CameraX 1.7.0, Lifecycle 2.11.1, and bundled ML Kit Face Detection 16.1.7. See the official Android and ML Kit documentation for library details.

## Start/stop monitoring and fresh-session capture

The Sensors tab now has explicit **Start monitoring** and **Stop monitoring** controls.

- Press **Start monitoring** to begin a new session. The app clears all previous in-memory readings first, so values from an earlier session cannot be reused.
- While monitoring is active, each sensor callback is recorded as an individual sample with sensor ID, sensor name/type, values, Android accuracy (when available), elapsed-realtime timestamp, and wall-clock timestamp.
- The live sensor cards show **No fresh reading in this monitoring session** until that specific sensor has emitted an event after Start was pressed.
- Press **Stop monitoring** to unregister listeners and freeze the session. The screen shows its duration, number of collected samples, and how many sensors produced fresh readings.
- Add an optional note and press **Encrypt and save session**. The encrypted entry stores session start/end times, latest fresh reading per sensor, and the full sample sequence collected during that session.
- One-shot and special-trigger sensors are registered using Android trigger APIs and re-armed after each trigger where supported.
- A session is held in memory until it is saved. Leaving the authenticated app or locking it cancels an active unsaved session. A 30,000-sample cap prevents unbounded memory growth in long sessions; the most recent samples are retained.

This is a session recording, not a guaranteed synchronized hardware capture: Android sensors report at different rates, and some may not produce any event during a short session. Such sensors remain marked as having no fresh reading instead of displaying an old value.
