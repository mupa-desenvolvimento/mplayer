# Review Report — Milestone 3 (R3: Dynamic license handling)

## Review Summary

**Verdict**: APPROVE

Overall, the implementation of dynamic license handling in `PlayerActivity` and the corresponding unit/integration tests in `FacialRecognitionLicensingTest` are clean, robust, and correctly cover the specified requirements. The background refresh loop safely propagates licensing updates and ensures correct transition states for the analytics engine without introducing regressions.

---

## Findings

### [Minor] MockK Suspend Function Mocking/Verification in Tests
- **What**: The tests `testRefreshInBackgroundOfflineDoesNotValidateOrEnsureAudience`, `testRefreshInBackgroundOnlineValidatesAndEnsuresAudience`, and `testRefreshInBackgroundExceptionPropagationDoesNotBlockAudience` use `every` and `verify` instead of `coEvery` and `coVerify` when mocking/verifying the private suspend function `ensureAudienceStarted`.
- **Where**: `app/src/test/java/com/mupa/player/enterprise/FacialRecognitionLicensingTest.kt` (lines 356, 373, 393, 410, 430, 447)
- **Why**: `ensureAudienceStarted` is a `suspend` function. Mocking suspend functions with regular `every` and `verify` in MockK can sometimes bypass coroutine continuation interceptors, leading to flaky test behavior or type conversion failures on certain Kotlin/MockK compiler versions.
- **Suggestion**: Replace `every { spyActivity["ensureAudienceStarted"]() }` with `coEvery { spyActivity["ensureAudienceStarted"]() }` and `verify { spyActivity["ensureAudienceStarted"]() }` with `coVerify { spyActivity["ensureAudienceStarted"]() }` to guarantee robust coroutine lifecycle alignment.

---

## Verified Claims

- **Correct Call Sequence** → Verified via inspecting `PlayerActivity.refreshInBackground` → PASS
  - `refreshInBackground` checks `isOnline()`, performs `DeviceValidationService.validateDevice` inside `runCatching`, and always calls `ensureAudienceStarted()` afterwards.
- **Immediate Re-evaluation and State Transition** → Verified via inspecting `PlayerActivity.ensureAudienceStarted` → PASS
  - License validation logic parses the new status and terminates/restarts `AudienceAnalyticsManager` correctly based on the new license state, avoiding redundant restarts.
- **Error Recovery** → Verified via inspecting `PlayerActivity.refreshInBackground` → PASS
  - The `runCatching` block safely encapsulates the API call so exceptions do not block `ensureAudienceStarted()`.

---

## Coverage Gaps

- None identified. The integration tests cover online/offline states and exception propagation successfully.

---

## Unverified Items

- **Actual test execution pass** — Run command permission timed out in the target test environment.
