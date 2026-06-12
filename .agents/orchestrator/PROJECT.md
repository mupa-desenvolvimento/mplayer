# Project: Native Android Face Recognition Migration

## Architecture
- `DeviceValidationService`: Communicates with Supabase RPC API. Parses JSON response to build `DeviceCache`.
- `DeviceCache` & `DeviceCacheManager`: Local storage of device config/license using SharedPreferences (legacy) and DataStore (modern).
- `PlayerActivity`: Main startup activity. Coordinates starting and stopping of the audience analytics flow based on license and hardware support.
- `AudienceAnalyticsManager` & `AudienceAnalyticsWebViewEngine` & `CameraX`: Manages camera capturing, image analysis, and web engine tracking.

## Code Layout
- `app/src/main/java/com/mupa/player/enterprise/managers/DeviceCacheManager.kt`
- `app/src/main/java/com/mupa/player/enterprise/services/DeviceValidationService.kt`
- `app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt`
- `app/src/main/java/com/mupa/player/enterprise/ui/SettingsActivity.kt`
- `app/src/main/java/com/mupa/player/enterprise/audience/AudienceAnalyticsManager.kt`

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | R1: Parse and store license | Parse and store `tipo_da_licenca` in DeviceValidationService, DeviceCache, and DeviceCacheManager | None | DONE |
| 2 | R2: Native Dependencies & Pipeline | Add ML Kit and TFLite libraries; implement native face detection and classification pipeline | M1 | PLANNED |
| 3 | R3: Integration & Toggles | Update PlayerActivity / AudienceAnalyticsManager to conditionally run the native pipeline | M2 | PLANNED |
| 4 | R4: Dynamic License Changes | Handle dynamic license updates for the native pipeline | M3 | PLANNED |
| 5 | R5: Final Verification & Audit | Verify native implementation via unit/E2E tests and Forensic Audit | M1, M2, M3, M4 | PLANNED |

## Interface Contracts
### `DeviceCache`
- `val tipoDaLicenca: String?` added to constructor.
- DataStore keys contain `"tipo_da_licenca"`.
- Legacy `SharedPreferences` keys contain `"tipo_da_licenca"`.

### `DeviceValidationService`
- Response parser extracts `tipo_da_licenca` and populates `DeviceCache`.

### Native Face Detection & Classification
- Use Google ML Kit Face Detection.
- Use TensorFlow Lite interpreter with local models.
- Store anonymous metrics in RAM and send to Supabase.

