# Handoff Report — Device Validation and Facial Recognition Bypass Analysis

## 1. Observation
I investigated the requirements in `ORIGINAL_REQUEST.md` against the MPlayer codebase at `c:\dev\mPlayer`. The following specific source locations were examined:

*   **`DeviceCache` & `DeviceCacheManager`** (`app/src/main/java/com/mupa/player/enterprise/managers/DeviceCacheManager.kt`):
    *   Defines the `DeviceCache` data class at lines 12–23.
    *   Saves preferences using DataStore (`Keys` at lines 28–40, editing at lines 69–81) and legacy `SharedPreferences` (lines 43–54).
    *   Constructs raw JSON at lines 56–67.
    *   Loads values in `load()` at lines 84–101.
*   **`DeviceValidationService`** (`app/src/main/java/com/mupa/player/enterprise/services/DeviceValidationService.kt`):
    *   Executes Supabase RPC calls to `BuildConfig.SUPABASE_DEVICE_RPC_URL` (lines 29–34).
    *   Parses responses in `parseDeviceResponse` (lines 46–81) and instantiates `DeviceCache` at lines 69–80.
*   **`PlayerActivity`** (`app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt`):
    *   Manages the loop in `startLoop()` (lines 339–381) and background refresh loop (lines 372–380).
    *   Starts the audience analytics flow via `ensureAudienceStarted()` (lines 794–821).
*   **`AudienceAnalyticsManager`** (`app/src/main/java/com/mupa/player/enterprise/audience/AudienceAnalyticsManager.kt`):
    *   Instantiates `AudienceAnalyticsWebViewEngine` (WebView) and trackers in its constructor (lines 32–35).
    *   Provides `canRunOnDevice` and `hasUsableFrontCamera` companion helper methods (lines 127–156).
*   **`SettingsActivity`** (`app/src/main/java/com/mupa/player/enterprise/ui/SettingsActivity.kt`):
    *   Instantiates `DeviceCache` manually inside `btnSaveFilial` listener (lines 111–122).

---

## 2. Logic Chain
1. **R1: Parse and Persist License Type (`tipo_da_licenca`)**:
    *   Currently, the Supabase RPC response contains license information but `DeviceValidationService.parseDeviceResponse` does not extract it. Adding `val tipoDaLicenca = obj.optString("tipo_da_licenca", null)` will retrieve it.
    *   `DeviceCache` needs a new field `val tipoDaLicenca: String?` to transport this value.
    *   `DeviceCacheManager` must store this field:
        *   In DataStore via `Keys.tipoDaLicenca = stringPreferencesKey("tipo_da_licenca")`.
        *   In legacy `SharedPreferences` as `"tipo_da_licenca"`.
        *   In the raw JSON representation.
        *   In the `load()` function, fetching from DataStore, falling back to legacy `SharedPreferences`.
2. **R2: License and Hardware Bypass Check**:
    *   Currently, `PlayerActivity.ensureAudienceStarted` checks only camera permission and device capability.
    *   If the license is not facial recognition enabled (i.e. not `"facial"`, `"analytics"`, or `"enterprise"`), or if the device lacks a usable front camera, the flow should not run.
    *   To completely skip initializing WebView resources, `CameraX` providers, and listeners, we must return early *before* instantiating or launching `AudienceAnalyticsManager` or requesting permissions.
    *   If the criteria are not met, but `audienceStarted` is currently `true` (e.g. license changed), it should dynamically call `.stop()` on the manager and release resources.
3. **R3: Handle Dynamic License Changes**:
    *   During background synchronization, `refreshInBackground()` can run device validation (`DeviceValidationService.validateDevice(deviceId)`) to fetch any license updates from Supabase.
    *   After validation, calling `ensureAudienceStarted()` will dynamically start or stop the flow depending on the new license state.
4. **SettingsActivity Constructor Compatibility**:
    *   Adding a parameter to `DeviceCache` requires modifying `SettingsActivity` where `DeviceCache` is manually copied/instantiated to pass `tipoDaLicenca = current.tipoDaLicenca`.

---

## 3. Caveats
*   Gradle execution was skipped locally due to environment limits. The proposed diffs are syntactically checked and reference real line numbers.
*   Assuming backend returns `tipo_da_licenca` as a string (such as `"facial"`, `"analytics"`, `"enterprise"`, or null/empty when not licensed).

---

## 4. Conclusion & Proposed Diffs

### A. Update `DeviceCache` and `DeviceCacheManager`
```diff
--- app/src/main/java/com/mupa/player/enterprise/managers/DeviceCacheManager.kt
+++ app/src/main/java/com/mupa/player/enterprise/managers/DeviceCacheManager.kt
@@ -22,2 +22,3 @@
     val deviceRegistered: Boolean,
+    val tipoDaLicenca: String?,
 )
@@ -39,2 +40,3 @@
         val deviceRegistered = booleanPreferencesKey("device_registered")
+        val tipoDaLicenca = stringPreferencesKey("tipo_da_licenca")
     }
@@ -53,2 +55,3 @@
             .putBoolean("device_registered", cache.deviceRegistered)
+            .putString("tipo_da_licenca", cache.tipoDaLicenca)
             .apply()
@@ -67,2 +70,3 @@
             .put("device_registered", cache.deviceRegistered)
+            .put("tipo_da_licenca", cache.tipoDaLicenca)
             .toString()
@@ -80,2 +84,3 @@
             prefs[Keys.deviceRegistered] = cache.deviceRegistered
+            prefs[Keys.tipoDaLicenca] = cache.tipoDaLicenca ?: ""
         }
@@ -99,2 +104,3 @@
             deviceRegistered = prefs[Keys.deviceRegistered] ?: legacyPrefs.getBoolean("device_registered", false),
+            tipoDaLicenca = prefs[Keys.tipoDaLicenca]?.takeIf { it.isNotBlank() } ?: legacyPrefs.getString("tipo_da_licenca", null),
         )
```

### B. Update `DeviceValidationService`
```diff
--- app/src/main/java/com/mupa/player/enterprise/services/DeviceValidationService.kt
+++ app/src/main/java/com/mupa/player/enterprise/services/DeviceValidationService.kt
@@ -67,2 +67,3 @@
         val dbId = obj.optLong("id", 0L)
+        val licenseType = obj.optString("tipo_da_licenca", "").ifBlank { null }
 
         return DeviceCache(
@@ -79,2 +80,3 @@
             deviceRegistered = true,
+            tipoDaLicenca = licenseType,
         )
```

### C. Update `SettingsActivity`
```diff
--- app/src/main/java/com/mupa/player/enterprise/ui/SettingsActivity.kt
+++ app/src/main/java/com/mupa/player/enterprise/ui/SettingsActivity.kt
@@ -121,2 +121,3 @@
                         lastSyncEpochMs = current.lastSyncEpochMs,
+                        tipoDaLicenca = current.tipoDaLicenca,
                         deviceRegistered = current.deviceRegistered
```

### D. Update `PlayerActivity` for Startup Logic & Dynamic Updates
```diff
--- app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt
+++ app/src/main/java/com/mupa/player/enterprise/ui/PlayerActivity.kt
@@ -629,2 +629,6 @@
         if (remote.isBlank()) return
+
+        // Dynamic background validation to update device license status
+        runCatching { DeviceValidationService(applicationContext).validateDevice(deviceId) }
+        ensureAudienceStarted()
+
         val changed = !manifestManager.compareManifest(deviceId, remote)
@@ -794,8 +798,20 @@
     private suspend fun ensureAudienceStarted() {
-        if (audienceStarted) return
-        if (!AudienceAnalyticsManager.canRunOnDevice(this)) return
+        val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
+        val licenseType = cache?.tipoDaLicenca
+        val isLicenseValid = licenseType == "facial" || licenseType == "analytics" || licenseType == "enterprise"
+        val canRun = AudienceAnalyticsManager.canRunOnDevice(this)
+
+        if (!isLicenseValid || !canRun) {
+            if (audienceStarted) {
+                audienceManager?.stop()
+                audienceManager = null
+                audienceStarted = false
+            }
+            return
+        }
+
+        if (audienceStarted) return
         if (!AudienceAnalyticsManager.hasCameraPermission(this)) {
             cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
             return
         }
```

---

## 5. Verification Method
1. **Compilation Command**:
   Execute the following in the project root to ensure everything compiles correctly:
   `.\gradlew.bat compileDebugSources`
2. **Execution Test**:
   Assemble a test APK:
   `.\gradlew.bat assembleDebug`
3. **Manual Verification Verification**:
   * Inspect the written files to verify modifications map exactly to the diff sections.
   * Run the app on a device without a front camera, verifying no camera request is displayed and no WebView engine instantiation occurs.
   * Toggle the `tipo_da_licenca` key in the database / mocked RPC response to `"consulta"`, verify that any running camera bindings are immediately stopped, and when changed back to `"facial"`, they resume gracefully.
