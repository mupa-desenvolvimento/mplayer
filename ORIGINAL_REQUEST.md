# Original User Request

## Initial Request — 2026-06-11T12:32:24-03:00

Scope planning and implementation of license-based and hardware-based toggles for the facial recognition feature in Mupa Player.

Working directory: c:\dev\mPlayer
Integrity mode: development

## Requirements

### R1. Parse and persist license type (`tipo_da_licenca`)
- Update `DeviceValidationService` to parse `tipo_da_licenca` from the Supabase RPC response.
- Update `DeviceCache` and `DeviceCacheManager` to store and persist `tipo_da_licenca` in both DataStore and legacy SharedPreferences.

### R2. License and hardware check for facial recognition initialization
- Modify the startup logic in `PlayerActivity` (`ensureAudienceStarted` or similar) to only initialize `AudienceAnalyticsManager` if the device has a usable front camera AND the cached `tipo_da_licenca` is exactly one of: `"facial"`, `"analytics"`, or `"enterprise"`.
- If the criteria are not met, the application must completely skip initializing the WebView engine (`AudienceAnalyticsWebViewEngine`), the CameraX provider, and any image analysis listeners, to conserve device resources (CPU, RAM, camera, battery).

### R3. Handle dynamic license changes
- Ensure that if the license type changes during a background refresh or synchronization, the app dynamically stops or starts the audience analytics flow as appropriate.

## Acceptance Criteria

### Licensing Logic and Persistence
- [ ] `DeviceCache` class contains the `tipoDaLicenca` field.
- [ ] `DeviceValidationService` correctly extracts `tipo_da_licenca` from Supabase's JSON response and saves it via `DeviceCacheManager`.
- [ ] If `tipo_da_licenca` is set to an unsupported value (e.g. `"consulta"`, `"televisao"` or null), `ensureAudienceStarted()` is not run, and camera resources are not bound.
- [ ] If `tipo_da_licenca` is exactly `"facial"`, `"analytics"`, or `"enterprise"`, and `hasUsableFrontCamera` is true, the facial recognition engine initializes successfully.

## Follow-up — 2026-06-11T15:35:21Z

Migrate Mupa Player's face recognition engine from WebView-based face-api.min.js to a native Android solution using Google ML Kit Face Detection and TensorFlow Lite (for age and gender classification), including licensing and front-camera validation toggles.

Working directory: c:\dev\mPlayer
Integrity mode: development

## Requirements

### R1. Parse and persist license type (`tipo_da_licenca`)
- Update `DeviceValidationService` to parse `tipo_da_licenca` from the Supabase RPC response.
- Update `DeviceCache` and `DeviceCacheManager` to store and persist `tipo_da_licenca` in both DataStore and legacy SharedPreferences.

### R2. Add Native Dependencies (ML Kit & TFLite)
- Integrate Google ML Kit Face Detection library (`com.google.mlkit:face-detection`) and TensorFlow Lite Support library (`org.tensorflow:tensorflow-lite-support` or similar) in `app/build.gradle.kts`.

### R3. Implement Native Face Detection & Classification Pipeline
- Completely replace `AudienceAnalyticsWebViewEngine` and `face-api.min.js` with a native processing flow:
  1. Capture frames from front camera using CameraX `ImageAnalysis`.
  2. Use Google ML Kit Face Detection to detect faces and compute bounding boxes.
  3. Crop face areas from frames.
  4. Run a TensorFlow Lite interpreter (with assets/downloaded models) to perform gender classification and age range estimation on the cropped faces.
  5. Store anonymous metrics in volatile memory (RAM).
  6. Send aggregated metrics to Supabase.

### R4. Conditional Initialization by License and Hardware
- In `PlayerActivity` (`ensureAudienceStarted`), check if front camera is present AND the cached `tipo_da_licenca` is exactly one of: `"facial"`, `"analytics"`, or `"enterprise"`.
- If either condition is not met, completely bypass the camera binding, ML Kit detector creation, and TensorFlow Lite model loading to save resources.

## Acceptance Criteria

### Build & Library Verification
- [ ] App compiles successfully with ML Kit and TensorFlow Lite dependencies.
- [ ] TensorFlow Lite age/gender custom models are correctly packaged/loaded.

### Logic and Execution
- [ ] If license is invalid or front camera is missing, camera resources are NOT bound and ML Kit/TFLite engines are not initialized.
- [ ] If license is valid and front camera exists, face detection and classification run natively.
- [ ] Face analysis output metrics are aggregated anonymously in RAM.

## Follow-up — 2026-06-11T15:37:00Z

O usuário deu aprovação explícita para o plano de implementação atualizado (migração nativa para Google ML Kit e TensorFlow Lite). Prossiga com a execução da tarefa.

## Follow-up — 2026-06-11T15:15:55-03:00

Atualizar toda a documentação e especificações do projeto Mupa Player Enterprise para seguir o padrão GitHub Spec Kit, refletindo a nova arquitetura de reconhecimento facial nativo (Google ML Kit + TensorFlow Lite com controle por licença), e implementar a lógica de provisionamento automático dos modelos TFLite nos dispositivos.

Working directory: c:\dev\mPlayer
Integrity mode: development

### Context
The project recently underwent a major architecture change: the facial recognition engine was migrated from WebView-based face-api.min.js to a fully native Android pipeline using Google ML Kit (face detection) and TensorFlow Lite (age/gender classification). Activation is now gated by:
1. Device has a usable front camera
2. `tipo_da_licenca` field (from Supabase `dispositivos` table) is exactly `"facial"`, `"analytics"`, or `"enterprise"`

Two TFLite model files are needed on the device at runtime:
- `age_gender_model.tflite` — age and gender classification
- `mobilefacenet.tflite` — face embedding for anonymous faceHash

These models currently have no automated distribution mechanism.

Key source files:
- AudienceAnalyticsManager.kt — orchestrates camera + engine lifecycle, includes license check
- AudienceAnalyticsNativeEngine.kt — ML Kit + TFLite engine (new)
- DeviceCacheManager.kt — persists tipoDaLicenca
- DeviceValidationService.kt — parses tipo_da_licenca from Supabase
- PlayerActivity.kt — calls ensureAudienceStarted() on startup and after each background sync
- app/build.gradle.kts — already includes ML Kit and TFLite dependencies

### Requirements

#### R1. Update all project specification documents
Review and rewrite the following files to accurately reflect the current architecture, following the GitHub Spec Kit format (clear sections, decision tables, field-level descriptions, code examples, and a changelog):
- MPLAYER_COMMANDS.md — add section for Audience Analytics / Facial Recognition feature, document tipo_da_licenca controls activation, list valid license types.
- MUPA_MDM_AGENT_INSTRUCOES.md — document Supabase RPC response now includes tipo_da_licenca and its effect; document new AudienceAnalyticsNativeEngine behavior.
- ARGOS_PLATFORM_AGENT_PROTOCOL.md — add documentation for relevant changes.
- README.md — write a useful project README covering: what the app is, architecture overview, key features (including facial recognition), how to build, and how the license system works.
Each document must include a ## Changelog section at the end.

#### R2. Implement TFLite model provisioning logic
Implement a mechanism to download and provision the TFLite model files to the device's files/models/ directory. The logic must:
- Download age_gender_model.tflite and mobilefacenet.tflite from a configurable URL (BuildConfig constant or manifest JSON).
- Only download if the device has a valid facial recognition license (tipo_da_licenca in "facial", "analytics", "enterprise").
- Only download if the files are missing or a newer version is available.
- Integrate into existing sync/startup flow so models are available before AudienceAnalyticsNativeEngine is initialized.
- Handle failure gracefully: if models unavailable, engine starts without them (existing fallback logic).

### Acceptance Criteria
- All 4 spec files updated with ## Changelog section.
- tipo_da_licenca and valid values documented in at least MPLAYER_COMMANDS.md and MUPA_MDM_AGENT_INSTRUCOES.md.
- Native engine architecture (ML Kit + TFLite) described in README.md with architecture flow.
- README.md includes build instructions (./gradlew assembleLegacyDebug / ./gradlew assembleModernDebug).
- Model download/provisioning function exists and called during startup/sync, before AudienceAnalyticsNativeEngine.init().
- App compiles successfully (BUILD SUCCESSFUL) after implementation.
- If device does not have valid license, model download is skipped entirely.
- If models already present and up-to-date, download is skipped.
- If download fails, app does not crash and engine starts in fallback mode.
