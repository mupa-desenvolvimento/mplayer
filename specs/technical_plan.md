# Technical Plan & Architecture: mPlayer Android App

This document describes the technical architecture, component breakdown, database interactions, and system designs for the **mPlayer** Android client application.

---

## 1. Technical Context

* **Language / Runtime**: Kotlin 1.9+, Java 17, Android SDK (Min API 21 for Legacy; Min API 24 for Modern).
* **Main Dependencies**:
  * *Android Jetpack*: CameraX, WorkManager, DataStore, AppCompat.
  * *Firebase*: Firebase Database (Realtime DB), Firebase Analytics.
  * *Supabase Client*: HTTP-based RPC calls to Supabase PostgreSQL endpoints.
  * *Google ML Kit*: Face Detection (com.google.mlkit:face-detection).
  * *TensorFlow Lite*: TFLite Runtime (`org.tensorflow:tensorflow-lite-support` and `org.tensorflow:tensorflow-lite-gpu` for acceleration delegates).
* **Storage**:
  * *Jetpack DataStore*: Key-value preferences for license caching.
  * *SharedPreferences*: Legacy settings and fallback storage.
  * *Local File System*: Internal storage directory `context.filesDir/models/` for TFLite models.
* **Network & API**: REST endpoints for Supabase, WebSocket for Firebase Realtime Database.
* **Performance Target**: Real-time TFLite face inference (>= 15 frames per second on Modern API 24+ devices with GPU delegate fallback to CPU).
* **Privacy/Compliance Constraints**: strictly zero persistent storage of visual data. Cropped face bitmaps must reside in transient JVM memory and be cleared immediately after feature extraction.

---

## 2. System Architecture

```
                                    ┌──────────────────────┐
                                    │     Supabase DB      │
                                    │ (Licensing & Events) │
                                    └──────────┬───────────┘
                                               │ (REST / RPC)
                                               ▼
┌─────────────────┐  (Wake-up)      ┌──────────────────────┐
│  Firebase RTDB  ├────────────────►│ DeviceCacheManager   │
│ (Remote Status) │                 │ (DataStore & ShPref) │
└─────────────────┘                 └──────────┬───────────┘
                                               │
                                               ▼
┌──────────────────┐  (Config)      ┌──────────────────────┐
│   PlayerActivity ├───────────────►│  Audience Analytics  │
│ (WebView Player) │                │  (CameraX + TFLite)  │
└──────────────────┘                └──────────────────────┘
```

---

## 3. Component Details

### A. Immersive Kiosk Player
* **Class**: `com.mupa.player.enterprise.ui.PlayerActivity`
* **WebView Config**:
  - JavaScript enabled.
  - DOM storage enabled.
  - Media playback without user gesture enabled.
  - Implements custom `WebChromeClient` and `WebViewClient` that monitors failures and triggers auto-reloads.
* **LockTask Mode**:
  - Leverages Android `DevicePolicyManager`.
  - When the app starts (and is registered as Device Owner), calls `startLockTask()`.
  - On `unlock_device` command, calls `stopLockTask()`.

### B. MDM Remote Syncing Daemon
* **Argos Pull Protocol**:
  - Scheduled periodically using `WorkManager` or triggered by Firebase Realtime DB notifications.
  - Queries `GET /commands/pending` from Argos API using device serial.
  - Dispatches results internally (e.g. `clear_cache`, `reset_app`, `abrir_url`).
  - Sends execution callback status via `POST /commands/ack` to the Argos API.
* **Firebase DB Listener**:
  - Attaches `ValueEventListener` to `commands/{device_id}` and `dispositivos/{device_id}`.
  - On callback trigger, immediately fires a job to fetch commands from Argos API.
* **Local HTTP Daemon**:
  - Embeds a lightweight NanoHTTPD or similar server listening on `127.0.0.1:8989`.
  - Translates local HTTP actions (e.g., `POST /lock`) into immediate activity events.

### C. License Verification System
* **RPC Validation**:
  - Executes RPC query `get_dispositivo_por_serial(serial_number)` on Supabase database.
  - Parses json body output containing `tipo_da_licenca`.
* **Caching Strategy**:
  - Reads/writes to Jetpack DataStore using a type-safe `DeviceCache` object.
  - Saves a mirroring string representation of the license to standard Android `SharedPreferences` for legacy subsystems.

### D. Audience Analytics Native Engine
* **Provisioning Flow**:
  - If a valid license exists, checking local folder `context.filesDir/models` for `mobilefacenet.tflite` and `age_gender_model.tflite`.
  - If files are missing, initiates downloads from `BuildConfig.TFLITE_MODELS_BASE_URL`.
  - Hashes files post-download to verify SHA-256 integrity.
* **Camera Analysis Loop**:
  - Configures CameraX `ImageAnalysis` with backpressure strategy `STRATEGY_KEEP_ONLY_LATEST`.
  - Converts output `ImageProxy` to InputImage for ML Kit Face Detector.
* **Inference Pipeline**:
  - Crop bitmap around the detected face bounding box.
  - Scale bitmap to $112 \times 112$ pixels for `mobilefacenet.tflite` to produce 128-float embedding.
  - Hash the embedding vector to create an anonymous visitor ID.
  - Scale bitmap to $224 \times 224$ pixels for `age_gender_model.tflite` to generate age (float) and gender probability array.
  - Aggregate statistics within RAM accumulator and discard cropped bitmap.
