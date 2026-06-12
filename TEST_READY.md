# E2E Test Suite Ready

## Test Runner
- Command: `.\gradlew.bat testModernDebugUnitTest` or double-clicking `run_tests.bat` at the project root.
- Expected: All tests pass with exit code 0.

## Coverage Summary
| Tier | Count | Description |
|------|------:|-------------|
| 1. Feature Coverage | 12 | Covers Supabase licensing key parsing, cache serialization to DataStore/SharedPreferences, camera capability checks, and dynamic transitions. |
| 2. Boundary & Corner | 15 | Covers malformed JSON responses, camera unavailability/blockages, permission states, and rapid dynamic updates. |
| 3. Cross-Feature | 3 | Covers playback impact on license transitions, Dev Mode watermark sync, and company registration changes. |
| 4. Real-World Application | 5 | Covers fresh installation initialization, hardware profile modifications, licensing revocation, and offline synchronization states. |
| **Total** | **35** | |

## Feature Checklist
| Feature | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---------|:------:|:------:|:------:|:------:|
| Parse & Persist License Type | 5 | 5 | ✓ | ✓ |
| License & Hardware Check for FR | 4 | 5 | ✓ | ✓ |
| Dynamic License Transitions | 3 | 5 | ✓ | ✓ |
