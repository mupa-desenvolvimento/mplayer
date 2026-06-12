# Scope: Milestone 3 (R3: Dynamic license handling)

## Architecture
- `PlayerActivity` refresh loop executes `refreshInBackground()` periodically.
- `refreshInBackground()` calls `DeviceValidationService.validateDevice()` to refresh license state.
- Immediately after, it calls `ensureAudienceStarted()` which should process the updated license type (`tipo_da_licenca`).
- Therefore, dynamic license changes are handled by the periodic refresh loop.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Exploration & Analysis | Verify `PlayerActivity.refreshInBackground` dynamic license handling and identify test gaps | None | DONE |
| 2 | Implementation / Verification | Ensure unit tests exist to verify dynamic behavior under `tipo_da_licenca` changes and all pass | M1 | DONE |
| 3 | Review | Run Reviewer to audit code and test changes | M2 | DONE |
| 4 | Audit Gate | Run Forensic Auditor to obtain CLEAN verdict | M3 | DONE |

## Interface Contracts
- `DeviceValidationService.validateDevice(deviceId)` updates device license state in storage/memory.
- `PlayerActivity.ensureAudienceStarted()` uses the updated license info to adjust player behavior.
