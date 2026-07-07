# Feature Specification: mPlayer Android App

This document defines the functional specifications, requirements, and user stories for the **mPlayer** Android client application.

---

## 1. Project Intent
mPlayer operates as a robust, specialized Android client application that displays remote digital signage web campaigns (CMS), enforces strict device lockdown (Kiosk mode), processes external hardware keyboard inputs (barcode scans), reacts to remote device management (MDM) commands via WebSockets/HTTP integration, and provides privacy-compliant audience demographics via native machine learning models.

---

## 2. User Scenarios & Testing

### User Story 1 - Uninterrupted Signage Playback (Priority: P1)
**Description**: As a digital signage operator, I want the player app to automatically launch in fullscreen and continuously load the Mupa CMS URL, even if the device restarts or loses internet connection momentarily.
* **Why this priority**: Core value of the application; if content is not rendering, the signage terminal is useless.
* **Independent Test**: Deploy the app, configure a CMS URL, disconnect Wi-Fi, and verify that the app handles connection issues gracefully (showing offline messages or loading locally cached versions) and resumes immediately when Wi-Fi is restored.
* **Acceptance Scenarios**:
  1. **Given** a device with internet access, **When** the app starts, **Then** it automatically enters immersive fullscreen and loads the configured CMS URL.
  2. **Given** a network loss during playback, **When** the WebView encounters a loading error, **Then** it automatically triggers a reload retry loop until the content is loaded successfully.

---

### User Story 2 - Remote MDM Device Lockdown & Kiosk Mode (Priority: P1)
**Description**: As a system administrator, I want to lock the Android device down so that users cannot exit the player app, open other system apps, or modify settings without my authorization.
* **Why this priority**: Prevents theft, vandalism, and unauthorized settings changes at physical terminals.
* **Independent Test**: Send a `lock_device` remote command, then physically press the Home and Recents keys on the device. Verify that the user remains locked inside MPlayer and no system menus are accessible.
* **Acceptance Scenarios**:
  1. **Given** a device provisioned as Device Owner, **When** a `lock_device` command is received, **Then** the app enters Android LockTask mode.
  2. **Given** a device in kiosk mode, **When** an `unlock_device` command is received, **Then** the app exits LockTask mode, permitting access to the Android launcher.

---

### User Story 3 - License-Gated Audience Analytics (Priority: P2)
**Description**: As an advertiser, I want to collect anonymous audience demographic metrics (age bracket and gender) from the camera feed, but only on devices that have purchased the premium analytics license.
* **Why this priority**: Crucial monetization model; analytics must not execute on unauthorized devices to conserve resources and enforce subscription tiers.
* **Independent Test**: Register a device in Supabase with `tipo_da_licenca = null` and confirm the camera stream/ML engine does not start. Update `tipo_da_licenca = "facial"`, trigger a sync, and verify the front camera activates and begins processing faces.
* **Acceptance Scenarios**:
  1. **Given** `tipo_da_licenca` is `null` or invalid, **When** the app initializes, **Then** the native audience analytics engine remains inactive.
  2. **Given` `tipo_da_licenca` is `"facial"`, `"analytics"`, or `"enterprise"`, **When** the app runs and a front-facing camera is present, **Then** the ML Kit + TFLite models are verified (downloaded if missing) and start processing.

---

### User Story 4 - Barcode Scanning & Product Consultation (Priority: P3)
**Description**: As a store customer, I want to scan a product barcode at a terminal, having the screen instantly load that product's details from the CMS web app.
* **Why this priority**: Enhances client interaction for price-check and product-info kiosk models.
* **Independent Test**: Connect a USB/Bluetooth keyboard scanner, input a barcode string followed by Enter, and check if the WebView receives a `consultaEAN` dispatch event with the barcode data.
* **Acceptance Scenarios**:
  1. **Given** the player is showing a compatible web page, **When** a barcode is scanned via keyboard input, **Then** the app dispatches the `consultaEAN` CustomEvent and triggers the JS function `window.consultarProduto(ean)`.

---

## 3. Functional Requirements

### R1. Immersive Fullscreen and Kiosk Enforcement
* Must hide system navigation bar, status bar, and disable pull-down quick settings panels.
* Must enforce Kiosk mode automatically on boot using a `DeviceAdminReceiver` (when set as Device Owner).

### R2. Dual-Channel MDM Control
* **Primary (Argos)**: Must poll the Argos API via GET requests periodically, execute instructions, and return execution results (ACK) using POST requests.
* **Wake-Up (Firebase RTDB)**: Must listen for database updates. On change, it immediately schedules a run of the Argos pull service to bypass polling latency.
* **Fallback (Direct Command)**: Must support direct legacy JSON commands pushed to `commands/{device_id}`.

### R3. Dynamic License Syncing
* Must perform periodic background license checks using a WorkManager job.
* Must cache license state in Jetpack DataStore and SharedPreferences to enable offline startup checks.

### R4. Native ML Analytics Pipeline
* Must use CameraX to capture frames in a background analyzer loop.
* Must crop bounding boxes of faces detected by ML Kit Face Detection.
* Must run cropped faces through:
  - `mobilefacenet.tflite` to compute embeddings for de-duplication.
  - `age_gender_model.tflite` to estimate age and gender probability.
* Must aggregate results over periodic windows and upload the metrics to Supabase without storing raw photos.
