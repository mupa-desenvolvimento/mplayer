# Project Constitution: mPlayer Enterprise

This document serves as the governing rulebook and "DNA" for the **mPlayer Enterprise** Android client application. All development, architecture decisions, and code modifications on the client app must adhere strictly to these principles.

---

## 1. Project Domain & Purpose

mPlayer Enterprise is an enterprise-grade Android digital signage player client with:
1. **CMS Playback**: High-performance, full-screen WebView content rendering.
2. **MDM Agent**: Remote device control capabilities and kiosk-mode containment.
3. **Audience Analytics**: Edge-computed, privacy-first demographic metrics (age/gender estimation via camera feed).

---

## 2. Core Technology Stack

* **Platform**: Android OS (API 21+ for Legacy, API 24+ for Modern build variants).
* **Language**: Kotlin (preferred) and Java.
* **Build Tool**: Gradle Kotlin DSL (`build.gradle.kts`).
* **Concurrency**: Kotlin Coroutines & Flows, Android WorkManager (for periodic background syncs).
* **MDM Client Integrations**: Argos API (HTTP Pull/ACK requests initiated by mPlayer) + Firebase Realtime Database (WebSocket wake-up).
* **Database & Cloud**: Supabase (PostgreSQL backend with RPC functions).
* **ML Inference**: CameraX (camera stream) + Google ML Kit (Face Detection) + TensorFlow Lite (TFLite) for age/gender classification and de-duplication.
* **Storage**: Android Jetpack DataStore (primary cached license state) + legacy SharedPreferences (fallback/compatibility).

---

## 3. Architecture & Core Guidelines

### A. Non-Negotiable Development Rules
1. **Fail-Safe Playback**: The primary function is content playback. If the MDM service, database sync, camera permission, or ML models fail, the WebView CMS player **must continue playing content** without crash or interruption.
2. **Privacy First (No Images Retained)**: No camera frames or face images may be saved to disk or transmitted over the network. All face hashes and embeddings are stored solely in transient RAM and discarded when the session ends.
3. **Strict License-Gating**: Native audience analytics (CameraX + ML Kit + TFLite) must only initialize if:
   - `tipo_da_licenca` is one of the validated values: `"facial"`, `"analytics"`, or `"enterprise"`.
   - A front-facing camera is physically present on the device.
4. **Offline Resilience**: If the internet connection drops, the app must load cached CMS content or play local offline files, while preserving local logs for later upload.

### B. Coding Standards
* **Null Safety**: Leverage Kotlin’s null safety. Avoid raw force-unwraps (`!!`).
* **Resource Cleanup**: Ensure CameraX analyzers, file streams, and database listeners are properly detached or closed when activities are paused or destroyed.
* **Security**: Never hardcode API keys, service-role tokens, or base URLs in the source files. Always use `BuildConfig` fields populated from `local.properties`.

---

## 4. Directory & Module Structure

* `/app` - Core Android application module.
  * `/src/main/java/com/mupa/player/enterprise`
    * `/mdm` - MDM logic, command handlers, and syncing.
    * `/analytics` - ML pipeline, camera feed processing, and model downloads.
    * `/ui` - Activities, WebView setups, and developer overlay.
* `/supabase` - Supabase schemas, migration scripts, and database RPC definitions.
* `/web_panel` - Companion web panel for administration/monitoring.
* `/specs` - Project specs and plans following GitHub Spec Kit.
