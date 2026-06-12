## 2026-06-11T17:47:24Z

Analyze the current codebase around AudienceAnalyticsManager, AudienceAnalyticsWebViewEngine, and any other relevant files. Find where we need to:
1. Add native dependencies (Google ML Kit Face Detection, TensorFlow Lite) in app/build.gradle.kts (and potentially settings or top-level gradle).
2. Migrate from AudienceAnalyticsWebViewEngine to a native pipeline:
   - Crop the CameraX frame.
   - Detect faces using Google ML Kit.
   - Perform age/gender inference using TensorFlow Lite on the cropped faces.
   - Aggregate/sync metrics to Supabase (check where Supabase client/API or database is used, e.g. AudienceSyncManager, SupabaseApi, ViewingSessionTracker).
Write a comprehensive implementation plan file under the orchestrator's directory: c:\dev\mPlayer\.agents\impl_orch_m2_gen2\implementation_plan.md
Include the exact dependencies to be added and how they are structured, the exact logic changes to be made, how to load the models, and how to verify it. Do NOT write or modify any code. Just write the implementation_plan.md.
