# E2E Test Infra: Mupa Player Facial Recognition Toggles

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on implementation design.
- Methodology: Category-Partition + BVA + Pairwise + Workload Testing.

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 | Tier 2 | Tier 3 |
|---|---------|---------------------|:------:|:------:|:------:|
| 1 | Parse and persist license type | ORIGINAL_REQUEST §R1 | 5      | 5      | ✓      |
| 2 | License & Hardware Check for FR | ORIGINAL_REQUEST §R2 | 5      | 5      | ✓      |
| 3 | Handle Dynamic License Changes | ORIGINAL_REQUEST §R3 | 5      | 5      | ✓      |

## Test Architecture
- Test runner: Instrumented AndroidJUnitRunner and JVM JUnit4 tests.
- Invocation: `./gradlew testModernDebugUnitTest` (for unit-level E2E tests) and `./gradlew connectedModernDebugAndroidTest` (for instrumented E2E tests).
- Directory layout:
  - `app/src/test/java/com/mupa/player/enterprise/` (Unit-level mock/E2E tests)
  - `app/src/androidTest/java/com/mupa/player/enterprise/` (Instrumented E2E tests)

## Test Cases

### Tier 1 - Feature Coverage (15 test cases)
- **FT1-1**: Parse `"facial"` license type from Supabase RPC response.
- **FT1-2**: Parse `"analytics"` license type from Supabase RPC response.
- **FT1-3**: Parse `"enterprise"` license type from Supabase RPC response.
- **FT1-4**: Verify `DeviceCacheManager` persists license type to DataStore.
- **FT1-5**: Verify `DeviceCacheManager` persists license type to SharedPreferences.
- **FT1-6**: Verify `PlayerActivity` starts AudienceAnalyticsManager when license is `"facial"` and camera is available.
- **FT1-7**: Verify `PlayerActivity` starts AudienceAnalyticsManager when license is `"analytics"` and camera is available.
- **FT1-8**: Verify `PlayerActivity` starts AudienceAnalyticsManager when license is `"enterprise"` and camera is available.
- **FT1-9**: Verify WebView engine, CameraX, and image analysis are skipped when license is `"consulta"`.
- **FT1-10**: Verify WebView engine, CameraX, and image analysis are skipped when license is `"televisao"`.
- **FT1-11**: Verify WebView engine, CameraX, and image analysis are skipped when license is null/absent.
- **FT1-12**: Verify WebView engine, CameraX, and image analysis are skipped when front camera is unavailable.
- **FT1-13**: Dynamic license transitions from `"facial"` to null/unsupported stops analytics.
- **FT1-14**: Dynamic license transitions from null to `"facial"` starts analytics.
- **FT1-15**: Dynamic license transitions from `"analytics"` to `"enterprise"` maintains analytics running without redundant restarts.

### Tier 2 - Boundary & Corner Cases (15 test cases)
- **FT2-1**: Supabase response contains malformed JSON or empty string (verify fallback/null license type, no crash).
- **FT2-2**: Supabase response contains `"tipo_da_licenca": "null"` (verify parsing to null, not string `"null"`).
- **FT2-3**: Very long license type value (verify storage/persistence handles arbitrary string lengths safely).
- **FT2-4**: Storage of license type when disk space is full / simulated IO error (graceful degradation, no crash).
- **FT2-5**: Device has front camera but it is blocked/busy by another app (graceful skip of FR initialization).
- **FT2-6**: Camera permission is denied by the user (ensure WebView and CameraX are completely skipped and resources released).
- **FT2-7**: Android SDK version < Oreo (Oreo is the minimum for analytics, check bypass logic/graceful skip).
- **FT2-8**: Rapid changes in dynamic license: toggle back and forth between `"facial"` and `"consulta"` multiple times in a second (concurrency check, no race conditions, correct final state).
- **FT2-9**: Parse license type when RPC response returns an empty array `[]` or nested objects.
- **FT2-10**: Cache contains an old license, and refresh returns null (verify old license is updated to null and analytics stops).
- **FT2-11**: Offline startup: no network, cached license exists (ensure cached license is used).
- **FT2-12**: Offline startup: no network, no cached license (ensure analytics skipped).
- **FT2-13**: Front camera exists but reports invalid characteristics (e.g. no lens facing front configuration) -> verify skipped.
- **FT2-14**: WebView fails to initialize (verify AudienceAnalyticsManager releases CameraX and stops gracefully).
- **FT2-15**: Rapid background sync while app is finishing/destroying (ensure no context leaks or crashes).

### Tier 3 - Cross-Feature Combinations (3 test cases)
- **FT3-1**: Playback active while license type changes from `"enterprise"` to `"consulta"` (verify playback continues unaffected but camera/WebView resource freed).
- **FT3-2**: Dev mode enabled with `"facial"` license (verify dev mode overlay and watermark updates correctly alongside FR status).
- **FT3-3**: Device registration changes company (verify company change triggers re-validation and correct license parse).

### Tier 4 - Real-World Application Scenarios (5 test cases)
- **FT4-1**: Fresh installation flow: Device registration -> Supabase RPC returns `"facial"` -> Camera permission granted -> PlayerActivity starts and initializes FR.
- **FT4-2**: Hardware upgrade/downgrade scenario: App starts on device with front camera and `"facial"` license -> camera is physically removed/disabled -> app stops FR.
- **FT4-3**: Remote licensing management: App is running, playing media -> administrator revokes license (changes to `"consulta"`) -> background sync detects it within 5 mins -> FR resources are torn down without stopping video playback.
- **FT4-4**: Re-licensing scenario: App is running with `"consulta"` -> admin assigns `"facial"` -> sync detects -> app prompts for Camera permission (or auto-starts if already granted) and spins up FR.
- **FT4-5**: Offline recovery scenario: App boots offline with cached `"facial"`, starts FR, then reconnects, syncs, gets updated `"analytics"` -> remains running smoothly.
