# GitHub Spec Kit: mPlayer Android App Specifications

Welcome to the specification repository for the **mPlayer** Android client application. This folder follows the **Spec-Driven Development (SDD)** guidelines via GitHub Spec Kit.

---

## Folder Map

This directory provides a single source of truth for developers, QA, and AI tools to understand the client-side architecture, features, and external integrations of the mPlayer application.

### 1. [Project Constitution](file:///c:/dev/mPlayer/specs/constitution.md)
* Defines core technologies, coding conventions, non-negotiable rules, directories, and architectural restrictions for the client app.
* **Key Guidelines**: Fail-safe playback priority, zero-visual-data privacy constraint for ML, and strict license gates.

### 2. [System Specification](file:///c:/dev/mPlayer/specs/system_spec.md)
* Outlines user stories, prioritized user journeys (P1–P3), functional requirements, and acceptance test cases for mPlayer.
* **Key Features**: Kiosk containment, WebView CMS playback, native ML analytics pipeline, and device owner client controls.

### 3. [Technical Plan & Architecture](file:///c:/dev/mPlayer/specs/technical_plan.md)
* Deep dive into target platform constraints, dependencies, component layouts (Kiosk Player, MDM client sync, local APIs, and TFLite model provisioning).
* Includes input/output dimensions for the ML Kit face-cropper, `mobilefacenet.tflite`, and `age_gender_model.tflite` pipelines.

### 4. [APIs, Protocols & Commands](file:///c:/dev/mPlayer/specs/api_and_commands.md)
* Direct reference manual for MDM command payloads (Firebase RTDB JSON commands), Argos HTTP Pull/ACK API request formats, and local HTTP control server endpoints handled by the app.
