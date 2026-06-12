# Scope: Milestone 2 — Native Dependencies & Pipeline

## Architecture
- ML/Computer Vision integration inside the Android Kotlin project (`c:\dev\mPlayer`).
- CameraX analyzer frame crop -> Google ML Kit Face Detection -> TensorFlow Lite Age/Gender Inference -> Supabase Metrics Aggregation.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Native Dependencies & Pipeline | Implement Android native pipeline & integrate ML models with Supabase metrics | none | PLANNED |

## Interface Contracts
- CameraX `ImageAnalysis.Analyzer` outputs cropped face bitmaps.
- ML Kit detects face bounding boxes.
- TensorFlow Lite model processes the face bitmap to output age/gender predictions.
- Predicted metrics are formatted and transmitted to Supabase dashboard/database.
