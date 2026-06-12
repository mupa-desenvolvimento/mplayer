# Handoff Report — Native ML Pipeline Migration

## 1. Observation

- **`app/build.gradle.kts` dependencies**: Add native libraries under `dependencies` block, and aaptOptions to avoid compressing `.tflite` model files in `android` block.
- **`AudienceAnalyticsManager.kt`**:
  - Instantiates `AudienceAnalyticsWebViewEngine` at line 33: `private val web = AudienceAnalyticsWebViewEngine(context, modelsDir)`
  - Converts frames in `onImage` at line 111-118:
    ```kotlin
    val jpeg = runCatching { YuvToJpeg.imageProxyToJpegBytes(image, jpegQuality = 55) }.getOrNull()
    image.close()
    if (jpeg == null || jpeg.isEmpty()) return

    val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
    ```
- **`AudienceAnalyticsWebViewEngine.kt`**:
  - Performs FNV-1a hashing on lines 115-122:
    ```javascript
    function fnv1a(str) {
      var h = 0x811c9dc5;
      for (var i = 0; i < str.length; i++) {
        h ^= str.charCodeAt(i);
        h = (h * 0x01000193) >>> 0;
      }
      return ('00000000' + h.toString(16)).slice(-8);
    }
    ```
  - Calculates face orientation checks on lines 135-152 (`isLooking`).
- **`AudienceSyncManager.kt`**:
  - Sends local Room session events (`AudienceSessionEntity`) to the server on lines 25-26:
    ```kotlin
    val baseUrl = SettingsManager(context).getSettings().serverUrl.trim().trimEnd('/')
    val url = "$baseUrl/api/audience/upload"
    ```

---

## 2. Logic Chain

1. Since we are migrating from a JavaScript-based engine run inside a WebView to local Android CPU/GPU execution, we need native bindings for ML Kit and TensorFlow Lite.
2. By adding `com.google.mlkit:face-detection`, `org.tensorflow:tensorflow-lite`, and `org.tensorflow:tensorflow-lite-support` to `app/build.gradle.kts`, we gain access to face bounding boxes and interpreter runtimes directly in Kotlin.
3. Cropping the image can be done using the `Face.boundingBox` coordinates returned by ML Kit Face Detection, applied directly to the image bitmap.
4. Using the cropped bitmap as input to the TFLite models for Age/Gender classification and Face Embeddings generates the age estimate, gender confidence, and face hash, respectively.
5. In Kotlin, recreating the FNV-1a hash matching the JavaScript logic guarantees continuity of identical face recognition tracking in `ViewingSessionTracker`.
6. Database persistence and network sync remain completely unchanged since they depend on `AudienceFrameResult` formats, keeping the schema database-agnostic.

---

## 3. Caveats

- Model files (`.tflite` for age/gender and embeddings) are assumed to be loaded dynamically from the existing `modelsDir` or bundled in assets. If they are not yet compiled/downloaded, they must be added before inference.
- Looking detection threshold is assumed to be 25 degrees on Euler Y yaw angle, which is a standard direct native approximation of the eyes/nose alignment ratio used in javascript.

---

## 4. Conclusion

The transition from a WebView-based inference process to a native pipeline with Google ML Kit Face Detection and TensorFlow Lite is highly feasible and structured in the provided implementation plan at `c:\dev\mPlayer\.agents\impl_orch_m2_gen2\implementation_plan.md`.

---

## 5. Verification Method

1. Verify dependency resolution and successful compilation by building the project:
   ```powershell
   ./gradlew assembleDebug
   ```
2. Verify test execution in Robolectric environment:
   ```powershell
   ./gradlew test
   ```
3. Inspect `c:\dev\mPlayer\.agents\impl_orch_m2_gen2\implementation_plan.md` to ensure correct structural mapping.
