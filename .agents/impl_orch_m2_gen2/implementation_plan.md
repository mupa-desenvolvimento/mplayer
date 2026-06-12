# Implementation Plan: Native ML Pipeline Migration (Milestone 2)

This plan outlines the migration from the JavaScript-based `AudienceAnalyticsWebViewEngine` to a native Android pipeline using Google ML Kit and TensorFlow Lite, improving reliability, speed, and CPU/memory utilization.

---

## 1. Native Dependencies

We will add the following dependencies and configurations in `app/build.gradle.kts`.

### Dependencies to Add

Add the following to the `dependencies` block:

```kotlin
// Google ML Kit Face Detection (local inference)
implementation("com.google.mlkit:face-detection:16.1.7")

// TensorFlow Lite runtime and support library
implementation("org.tensorflow:tensorflow-lite:2.14.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
```

### AAPT Options Configuration

To prevent Android Gradle Plugin from compressing `.tflite` model files in the APK (which allows them to be memory-mapped directly), add the following under the `android` block:

```kotlin
android {
    ...
    @Suppress("UnstableApiUsage")
    aaptOptions {
        noCompress("tflite")
    }
}
```

---

## 2. ML Models Structure

The native pipeline requires the following models:
1. **Age/Gender Classification Model**: A TFLite model (e.g., `age_gender_model.tflite`) that takes a cropped face image and outputs age and gender predictions.
2. **Face Recognition / Embedding Model** (Optional but recommended to match WebView feature tracking): A TFLite model (e.g., `mobilefacenet.tflite`) that extracts a 128-dimensional embedding from the cropped face to generate the identical `faceHash` format.

These models will be stored in the app's assets directory (`app/src/main/assets/models/`) or downloaded dynamically to `context.filesDir/models` (following the existing dynamic models logic).

---

## 3. Native Pipeline Architecture & Logic Changes

We will replace the WebView-based `AudienceAnalyticsWebViewEngine` with a native `AudienceAnalyticsNativeEngine` class.

### Step-by-Step Logic in `AudienceAnalyticsNativeEngine`

#### A. Initialize Engine and Load Models
- Load models from Assets/Files directory as memory-mapped buffers (`MappedByteBuffer`).
- Instantiate the TFLite `Interpreter` objects for age/gender and embeddings.
- Initialize Google ML Kit `FaceDetector` with performance options:
  ```kotlin
  val options = FaceDetectorOptions.Builder()
      .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
      .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
      .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
      .build()
  val faceDetector = FaceDetection.getClient(options)
  ```

#### B. Image Processing & Face Crop (`ImageAnalysis.Analyzer`)
1. **Image Conversion**: In `AudienceAnalyticsManager.kt`'s `onImage(image: ImageProxy)`:
   - Convert the `ImageProxy` to a rotated `Bitmap` using existing YUV-to-Bitmap conversions or using CameraX 1.3+ native `image.toBitmap()`.
   - Maintain correct orientation by applying rotation from `image.imageInfo.rotationDegrees`.
2. **Face Detection**:
   - Create an ML Kit `InputImage` from the bitmap: `val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)`.
   - Call `faceDetector.process(inputImage)` asynchronously (or synchronously on the background analyzer thread).
3. **Face Crop**:
   - For each detected `Face`, retrieve the bounding box: `val bounds = face.boundingBox`.
   - Crop the face from the rotated bitmap:
     ```kotlin
     val x = bounds.left.coerceAtLeast(0)
     val y = bounds.top.coerceAtLeast(0)
     val w = bounds.width().coerceAtMost(rotatedBitmap.width - x)
     val h = bounds.height().coerceAtMost(rotatedBitmap.height - y)
     val croppedFaceBitmap = Bitmap.createBitmap(rotatedBitmap, x, y, w, h)
     ```

#### C. Run TFLite Age/Gender Inference
- Resize `croppedFaceBitmap` to the model's required dimensions (e.g. `224x224` or `112x112`).
- Normalize the pixels into a float `ByteBuffer`.
- Run the TFLite interpreter and map the output outputs:
  - Extract estimated age (continuous float or age group probability).
  - Extract gender (probability of male/female).

#### D. Calculate Look Detection (`isLooking`)
Instead of calculating distances between facial landmarks manually, ML Kit provides head rotation angles directly:
- `isLooking = Math.abs(face.headEulerAngleY) < 25.0f` (checks if the user is facing within 25 degrees of the camera).

#### E. Generate `faceHash` (FNV-1a Hash)
- Run the Face Recognition model on the cropped face to get a float array descriptor.
- Generate a FNV-1a hash of the descriptor to ensure backward compatibility:
  ```kotlin
  fun fnv1a(str: String): String {
      var h = 0x811c9dc5L
      for (i in str.indices) {
          h = h xor str[i].code.toLong()
          h = (h * 0x01000193) and 0xffffffffL
      }
      return String.format("%08x", h)
  }
  ```

---

## 4. Supabase Metrics Aggregation

No changes are required for `AudienceSyncManager` or the Room database entities (`AudienceSessionEntity`), because:
- The native pipeline outputs a standard `AudienceFrameResult` containing a list of `DetectedFace` objects.
- `AudienceAnalyticsManager` forwards this list directly to `ViewingSessionTracker.onFrame(...)`.
- `ViewingSessionTracker` constructs `AudienceSessionEntity` records, which are then synchronized to Supabase by `AudienceSyncManager` via the existing OkHttp/Retrofit REST API client.

---

## 5. Verification Plan

### Unit Tests (`FacialRecognitionLicensingTest.kt`)
- Modify setup in `setUp()` to mock the native engine instead of `AudienceAnalyticsWebViewEngine`.
- Add unit tests verifying:
  1. Image rotation and cropping boundary checks.
  2. The `fnv1a` hashing function yields correct hexadecimal outputs.
  3. Look detection checks using mock ML Kit Euler angles.

### Compile and Run Tests
Run the following tasks to verify the build and test changes:
```powershell
# Clean build
./gradlew clean

# Run unit tests to verify no regressions in licensing logic
./gradlew test
```
