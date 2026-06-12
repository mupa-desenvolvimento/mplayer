# Project: Facial Recognition Toggle

## Architecture
- `DeviceCache`: Data class holding `tipoDaLicenca: String?`
- `DeviceCacheManager`: Handles DataStore and legacy SharedPreferences reads/writes
- `DeviceValidationService`: Calls Supabase RPC API, parses response, updates DeviceCacheManager
- `PlayerActivity`: Runs startup/background loop, checks permissions, checks license & camera, controls `AudienceAnalyticsManager` lifecycle
- `SettingsActivity`: Manages manual save, compatibility copy

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | E2E Testing Suite Setup | Create opaque-box E2E test cases covering licensing logic and hardware presence. | none | IN_PROGRESS |
| 2 | License Parsing & Storage | Update DeviceCache, DeviceCacheManager, DeviceValidationService, SettingsActivity. | none | PLANNED |
| 3 | License & Hardware Bypass | Update PlayerActivity startup logic to bypass/teardown CameraX/WebView if license or camera checks fail. | M2 | PLANNED |
| 4 | Dynamic Updates | Handle dynamic refresh logic in background thread. | M2, M3 | PLANNED |

## Interface Contracts
### DeviceCache
- `tipoDaLicenca: String?`: Licensed type value parsed from RPC (e.g. `"facial"`, `"analytics"`, `"enterprise"`)

### DeviceCacheManager
- `save(cache: DeviceCache)`: Saves DeviceCache to DataStore and legacy SharedPreferences
- `load(): DeviceCache?`: Retrieves DeviceCache

### DeviceValidationService
- `validateDevice(deviceId: String): DeviceValidationResult`
- `parseDeviceResponse(json: String): DeviceCache?`

## Code Layout
- `app/src/main/java/com/mupa/player/enterprise/managers/DeviceCacheManager.kt`
- `app/src/main/java/com/mupa/player/enterprise/services/DeviceValidationService.kt`
- `app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt`
- `app/src/main/java/com/mupa/player/enterprise/ui/SettingsActivity.kt`
