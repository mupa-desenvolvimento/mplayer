# Mupa Player Enterprise

**Android digital signage player with MDM capabilities and license-gated audience analytics.**

---

## What is Mupa Player Enterprise?

Mupa Player Enterprise is an Android application designed for **digital signage deployments** in retail, hospitality, and corporate environments. It combines:

- **Content playback** — WebView-based player supporting video, images, and HTML pages delivered from the Mupa CMS.
- **MDM (Mobile Device Management) agent** — Remote device control via Firebase Realtime Database and the Argos platform.
- **License-gated audience analytics** — Native ML pipeline (ML Kit + TFLite) for anonymous audience measurement using the device front camera. Only activates when the device has a valid `tipo_da_licenca` in Supabase.

---

## Architecture Overview

```
┌───────────────────────────────────────────────────────────────┐
│                    Mupa Player Enterprise                     │
│                                                               │
│  ┌─────────────────────────────────────────────┐             │
│  │  Content Playback Layer                     │             │
│  │  WebView  ←→  Mupa CMS (video/image/HTML)   │             │
│  └─────────────────────────────────────────────┘             │
│                                                               │
│  ┌─────────────────────────────────────────────┐             │
│  │  MDM Agent Layer                            │             │
│  │  Firebase RTDB (wake-up + legacy commands)  │             │
│  │  Argos API (primary command channel)        │             │
│  │  Supabase (device validation + licensing)   │             │
│  └─────────────────────────────────────────────┘             │
│                                                               │
│  ┌─────────────────────────────────────────────┐             │
│  │  Audience Analytics Layer  [license-gated]  │             │
│  │  CameraX → ML Kit → TFLite → Supabase       │             │
│  │  Activated only when tipo_da_licenca is      │             │
│  │  "facial", "analytics", or "enterprise"     │             │
│  └─────────────────────────────────────────────┘             │
└───────────────────────────────────────────────────────────────┘
```

---

## Key Features

| Feature | Description |
|---|---|
| **Content playback** | Video, images, HTML via WebView. Full-screen, auto-reload on error. |
| **Kiosk / Device lock** | Lock-task mode + fullscreen enforcement. Requires Device Owner for full MDM power. |
| **Barcode scan** | EAN lookup via wedge keyboard; triggers `consultaEAN` JS event in WebView. |
| **Remote commands — Argos** | Primary command channel: pull-based polling from Argos API with ACK. |
| **Remote commands — Firebase** | Wake-up channel; triggers immediate Argos sync when any change detected. |
| **Remote commands — Legacy** | Direct JSON commands via Firebase RTDB (`commands/{device_id}`). |
| **Local API** | `http://127.0.0.1:8989` — lock, unlock, reload, command endpoints for local automation. |
| **Device reboot** | Remote reboot (requires Device Owner). |
| **App whitelist** | Define allowed apps in kiosk mode. |
| **Audience analytics** | Anonymous facial metrics (age bracket + gender probability). **License-gated.** |

---

## Build Instructions

### Prerequisites

- Android Studio Hedgehog or later (or Gradle CLI)
- JDK 17+
- `local.properties` at the project root with at minimum:

```properties
# Required: Supabase service-role token for device validation
SUPABASE_TOKEN=your_supabase_service_role_token_here

# Required: Base URL for TFLite model downloads (no trailing slash)
TFLITE_MODELS_BASE_URL=https://your-model-server.example.com/models
```

### Build variants

| Variant | API level | Command |
|---|---|---|
| Legacy Debug | API 21+ (Android 5.0+) | `./gradlew assembleLegacyDebug` |
| Legacy Release | API 21+ | `./gradlew assembleLegacyRelease` |
| Modern Debug | API 24+ (Android 7.0+) | `./gradlew assembleModernDebug` |
| Modern Release | API 24+ | `./gradlew assembleModernRelease` |

> **Tip**: Use the `Modern` variant for devices running Android 7.0 or later — it enables additional optimizations and uses newer APIs.

### Install on device

```bash
adb install -r app/build/outputs/apk/modern/debug/app-modern-debug.apk
```

---

## License System

### `tipo_da_licenca` field

Each registered device in the Supabase `dispositivos` table has a `tipo_da_licenca` column that controls which premium features are enabled.

| `tipo_da_licenca` value | Facial Recognition / Audience Analytics | Notes |
|---|---|---|
| `"facial"` | ✅ Enabled | Full facial recognition + anonymous metrics |
| `"analytics"` | ✅ Enabled | Same engine as `"facial"` |
| `"enterprise"` | ✅ Enabled | Enterprise tier — all features unlocked |
| `null` | ❌ Disabled | Device unregistered or no analytics license |
| Any other string | ❌ Disabled | Unrecognized value; feature silently skipped |

The license is refreshed on **every background device sync** (WorkManager periodic task). A license change in Supabase takes effect on the next sync without requiring an app reinstall or device reboot.

### How the check works

1. MPlayer calls Supabase RPC `get_dispositivo_por_serial` with the device serial.
2. The RPC returns `tipo_da_licenca`.
3. `DeviceCacheManager` persists the value in both DataStore and SharedPreferences.
4. At startup (and after each sync), the audience analytics engine checks the cached value against the allowed set `{"facial", "analytics", "enterprise"}`.
5. If the value matches **and** a front camera is detected, the engine starts.

---

## Audience Analytics: ML Kit + TFLite Flow

### Hardware requirement

The device must have a **front-facing camera** detected via `CameraManager` (`LENS_FACING_FRONT`). If absent, the engine does not start (even with a valid license).

### Pipeline

```
Front Camera
    │  (CameraX ImageAnalysis — continuous stream)
    ▼
ML Kit Face Detection
    │  detects face bounding boxes per frame
    ▼
Face Crop (one crop per detected face)
    │
    ├──► mobilefacenet.tflite  [112 × 112 px input]
    │       → 128-float embedding → anonymous faceHash
    │
    └──► age_gender_model.tflite  [224 × 224 px input]
            → age (float, years) + [male_prob, female_prob]
    │
    ▼
In-RAM anonymous accumulator
    │  keyed by faceHash — no image ever retained
    │  rolling time-window aggregation
    ▼
Supabase metrics table  (no PII, no images)
```

### TFLite models

| File | Input | Output | Notes |
|---|---|---|---|
| `age_gender_model.tflite` | 224 × 224 × 3 RGB float | `[age_float, male_prob, female_prob]` | Not bundled in APK |
| `mobilefacenet.tflite` | 112 × 112 × 3 RGB float | 128-float embedding | Not bundled in APK |

### Model provisioning

Models are downloaded at runtime by `ModelProvisioningManager` from `BuildConfig.TFLITE_MODELS_BASE_URL`. They are saved to `context.filesDir/models/` and verified with SHA-256 before the engine starts. If download fails or integrity check fails, the engine is skipped and content playback continues normally.

---

## Remote Command Channels

| Channel | Protocol | Usage |
|---|---|---|
| **Argos API** | HTTP pull (polling) | Primary — MPlayer polls for pending commands, ACKs results |
| **Firebase RTDB** | WebSocket listener | Wake-up only — triggers immediate Argos sync |
| **Legacy Firebase** | `commands/{device_id}` path | Legacy direct command format (backward compatible) |
| **Local API** | `http://127.0.0.1:8989` | Internal automation (lock, unlock, reload, command) |

See [`MPLAYER_COMMANDS.md`](MPLAYER_COMMANDS.md) for the full command reference.  
See [`ARGOS_PLATFORM_AGENT_PROTOCOL.md`](ARGOS_PLATFORM_AGENT_PROTOCOL.md) for the Argos protocol specification.  
See [`MUPA_MDM_AGENT_INSTRUCOES.md`](MUPA_MDM_AGENT_INSTRUCOES.md) for MDM agent instructions.

---

## Project Structure (key components)

```
app/
├── src/main/
│   ├── java/com/mupa/player/enterprise/
│   │   ├── PlayerActivity          — Main WebView player
│   │   ├── mdm/                    — MDM agent (Firebase + Argos)
│   │   │   ├── ArgosCommandService — Argos pull/ACK service
│   │   │   ├── DeviceCacheManager  — Persists device info & license
│   │   │   └── SupabaseValidator   — Calls get_dispositivo_por_serial
│   │   └── analytics/              — Audience analytics pipeline
│   │       ├── AudienceAnalyticsNativeEngine
│   │       ├── ModelProvisioningManager
│   │       └── FaceAnalysisPipeline
│   └── res/
├── build.gradle                    — Build config (flavors: legacy/modern)
└── local.properties                — Secrets (not committed)
```

---

## Changelog

| Version | Date | Description |
|---|---|---|
| 1.0.0 | 2024-03-01 | Initial release — content playback + Firebase MDM |
| 1.1.0 | 2024-06-01 | Argos command channel (pull-based) replaces Firebase as primary |
| 1.2.0 | 2025-01-15 | Supabase device validation + `tipo_da_licenca` licensing |
| 1.3.0 | 2026-06-11 | Native AudienceAnalyticsEngine (ML Kit + TFLite) replaces face-api.min.js |
