# Explorer Report — Dynamic License Handling in PlayerActivity

## Executive Summary
This report analyzes dynamic license handling in `PlayerActivity` within its background refresh loop (`refreshInBackground()`), verifies call ordering, dynamic database/cache propagation, and examines existing unit test coverage.

---

## 1. Call Sequence Investigation
### Question
*Does `PlayerActivity.refreshInBackground()` correctly call `DeviceValidationService.validateDevice` and then `ensureAudienceStarted`?*

### Observations
In `app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt` (lines 627–632):
```kotlin
    private suspend fun refreshInBackground() {
        if (!isOnline()) return
        runCatching {
            com.mupa.player.enterprise.services.DeviceValidationService(applicationContext).validateDevice(deviceId)
        }
        ensureAudienceStarted()
```

- **Execution Flow**:
  1. **Connectivity Check**: Checks `isOnline()`. If offline, execution halts immediately.
  2. **Device Validation**: Instantiates `DeviceValidationService` and calls `validateDevice(deviceId)` inside `runCatching { ... }`. This prevents any network exception or RPC payload parsing failure from crashing the background loop.
  3. **Audience Start Verification**: Calls `ensureAudienceStarted()` directly after the `runCatching` block.
- **Robustness**: Because `ensureAudienceStarted()` is called outside/after the `runCatching` block, the activation/deactivation logic always executes, even if the API request fails (relying on the last cached value).

---

## 2. Dynamic License Changes (`tipo_da_licenca`)
### Question
*Are there changes in `tipo_da_licenca` (license type) that are dynamic?*

### Observations
1. **Network Update**:
   - `DeviceValidationService.validateDevice()` executes a POST to `SUPABASE_DEVICE_RPC_URL` (lines 28–34).
   - In `parseDeviceResponse` (lines 68–81):
     ```kotlin
     val licenseType = obj.optString("tipo_da_licenca", "").takeIf { it.isNotBlank() && it != "null" }
     return DeviceCache(
         ...
         tipoDaLicenca = licenseType
     )
     ```
   - The response is persisted immediately using `cacheManager.save(parsed)` (line 39).
2. **Dynamic Application on Refresh**:
   - During the 5-minute background loop, `refreshInBackground()` calls `validateDevice()`, which retrieves the updated license type from Supabase and overwrites the local cache.
   - Immediately following, `ensureAudienceStarted()` executes:
     - Reads the updated `tipoDaLicenca` from `DeviceCacheManager` (line 800–801).
     - Validates if the license permits analytics (lines 802–803):
       ```kotlin
       val licenseValid = licenseType == "facial" || licenseType == "analytics" || licenseType == "enterprise"
       ```
     - **Deactivation**: If the license changes from a valid option (e.g. `facial`) to an invalid option (e.g. `consulta` or `null`), and `audienceStarted` is currently `true`, it terminates the analytics engine:
       ```kotlin
       if (!licenseValid || !canRun) {
           if (audienceStarted) {
               audienceManager?.stop()
               audienceManager = null
               audienceStarted = false
               Log.i("PlayerActivity", "...")
           }
           return
       }
       ```
     - **Activation**: If the license transitions from invalid to valid and camera hardware is ready, it requests permission or starts the engine.
- **Conclusion**: Yes, `tipo_da_licenca` changes are dynamically propagated from the backend, saved to cache, and applied within the next background execution cycle.

---

## 3. Unit Test Coverage & Gaps
### Existing Unit Tests
The unit test file `app/src/test/java/com/mupa/player/enterprise/FacialRecognitionLicensingTest.kt` exists and covers:
1. **RPC Parsing**: Tests that license strings `"facial"`, `"analytics"`, and `"enterprise"` are correctly parsed into `DeviceCache`.
2. **Persistence**: Tests that `DeviceCacheManager` properly saves and reads the license type to and from SharedPreferences/DataStore.
3. **Manager Startup**: Tests that `AudienceAnalyticsManager` starts or skips correctly based on the cache's license type.
4. **Transition Simulation**: The test `testDynamicLicenseTransitions` simulates the logic of `ensureAudienceStarted` when license states change step-by-step (e.g., from `facial` -> `enterprise` -> `consulta` -> `facial`).

### Identified Gaps (Missing Tests)
1. **Direct `PlayerActivity.refreshInBackground` Integration Test**:
   - No unit test uses `PlayerActivity` directly to verify that `refreshInBackground` triggers both `validateDevice` and `ensureAudienceStarted` in the expected sequence.
2. **Error Recovery Verification**:
   - There are no tests verifying that `ensureAudienceStarted()` is still called if `DeviceValidationService.validateDevice()` throws an exception.
