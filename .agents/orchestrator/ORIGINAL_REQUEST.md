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
