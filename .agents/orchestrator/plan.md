# Implementation Plan - License and Hardware-based Toggles for Facial Recognition

## Phase 1: Planning and Codebase Analysis
- Explore DeviceValidationService, DeviceCache, DeviceCacheManager, PlayerActivity, and AudienceAnalyticsManager to understand existing structure.
- Define specific milestones and draft the PROJECT.md.
- Establish E2E testing framework/infrastructure requirement.

## Phase 2: Dual-Track Execution
- **E2E Testing Track**:
  - Implement E2E test cases verifying different license configurations and camera presence states.
  - Publish TEST_READY.md.
- **Implementation Track**:
  - Milestone 1: Parse and store `tipo_da_licenca` in DeviceValidationService, DeviceCache, and DeviceCacheManager. (DONE)
  - Milestone 2: Add native dependencies (ML Kit & TFLite) and implement native face detection and classification pipeline.
  - Milestone 3: Integrate native pipeline into startup flow with license & hardware check (PlayerActivity / AudienceAnalyticsManager).
  - Milestone 4: Handle dynamic license updates.

## Phase 3: Verification and Hardening
- Run E2E tests against the implementation.
- Challenger/Adversarial testing.
- Forensic Auditor verify integrity.

## Phase 4: Final Synthesis & Handover
- Collect and synthesize results.
- Write handoff.md.
