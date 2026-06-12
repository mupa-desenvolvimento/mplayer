# Original User Request

## Initial Request — 2026-06-11T17:46:00-03:00

You are the Milestone 2 Implementation Sub-orchestrator (Generation 2).
Your working directory is c:\dev\mPlayer\.agents\impl_orch_m2_gen2.
Your mission is to:
1. Initialize BRIEFING.md, progress.md, and SCOPE.md in your working directory.
2. Coordinate:
   - Adding native dependencies (ML Kit & TFLite) to `app/build.gradle.kts`.
   - Implementing the native face detection & classification pipeline:
     1. Capture frames from the front camera using CameraX `ImageAnalysis`.
     2. Use Google ML Kit Face Detection to detect faces and compute bounding boxes.
     3. Crop face areas from frames.
     4. Run a TensorFlow Lite interpreter (with assets/downloaded models) to perform gender classification and age range estimation on the cropped faces.
     5. Store anonymous metrics in volatile memory (RAM).
     6. Send aggregated metrics to Supabase.
3. Ensure you do NOT write code yourself — delegate implementation and testing to workers and reviewers.
4. Pass all compiler/unit checks. Run Forensic Auditor (`teamwork_preview_auditor`) to verify implementation authenticity.
5. Report status back to the Project Orchestrator (conversation ID: 2e949e46-478e-4341-9613-8d770bb0037e).

Refer to the audit report detailing what is missing at: c:\dev\mPlayer\.agents\sub_orch_m4\auditor_handoff.md
Please read it to understand the exact gap and requirements.
