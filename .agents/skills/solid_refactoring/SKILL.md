---
name: solid_refactoring
description: "Guidelines and reference patterns for refactoring the mPlayer codebase to adhere to SOLID principles and clean design patterns."
---

# SOLID Refactoring Guidelines for mPlayer

Use this skill when making structural changes or adding new features in mPlayer to ensure we maintain clean, decoupled code.

## 1. Single Responsibility Principle (SRP)
- Keep classes focused on one actor or reason to change.
- **Example:** Avoid combining HTTP network requests, Room Database caching, and image downloading in a single "Engine" class (e.g., `PriceQueryEngine`). Separate them into `PriceNetworkService`, `PriceCacheRepository`, and `ProductImageManager`.

## 2. Dependency Inversion Principle (DIP) & Strategy Pattern
- High-level modules must depend on abstractions, not concrete implementations.
- Always create common interfaces for classes sharing behavior (e.g., ML Engines, Player engines).
- **Example:**
  ```kotlin
  interface AudienceAnalyticsEngine {
      suspend fun init(): Boolean
      suspend fun processFrameJpegBase64(base64Jpeg: String, rotationDegrees: Int): AudienceFrameResult
  }
  ```
- Inject dependencies via constructors instead of hardcoding `new` / instantiation inside classes.

## 3. Double Buffering Pattern
- When dealing with multimedia, maintain active and inactive layers (e.g., `LayerViews`).
- Preload upcoming content in the inactive layer, switch when ready, and keep visibility transitions clean.
