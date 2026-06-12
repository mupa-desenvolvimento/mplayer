# Scope: Milestone 2 (R2: License and camera checks)

## Architecture
- `PlayerActivity` controls the application loop and checks license status and camera features.
- `AudienceAnalyticsManager` manages initialization and operation of WebView, CameraX, and listeners.
- The early bypass should check `DeviceCache.tipoDaLicenca` and `AudienceAnalyticsManager.canRunOnDevice` inside `ensureAudienceStarted` to bypass initialization completely if conditions are not met, and to stop any active tracking if previously running.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | R2 Implementation | Implement license and camera validation startup bypass and dynamic shutdown in PlayerActivity | Milestone 1 (R1) | DONE |
| 2 | Unit Tests & Verification | Implement unit tests verifying both success (valid license & camera) and fail cases (invalid license/no camera) | R2 Implementation | DONE |

## Interface Contracts
- `ensureAudienceStarted` dynamic early return/early exit and stop logic.
- Tests to run: `PlayerActivityTest` and other relevant tests.
