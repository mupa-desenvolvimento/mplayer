[OPEN] Argos Agent startup crash

## Session
- id: argos-agent-startup-crash
- repo: c:\src\mupa_player_enterprise
- date: 2026-06-15

## Symptom
- Agent (com.mupa.agent.argos) crashes when starting / on launch.

## Hypotheses (falsifiable)
1) Crash is triggered during app init (Application / ContentProvider init) due to manifest/provider/permission misconfiguration (e.g., authority collision, missing class, SecurityException).
2) Crash is triggered when starting the Launcher/Setup activity due to missing resource/classpath or runtime-only dependency (ClassNotFoundException/NoClassDefFoundError).
3) Crash is triggered by Device Owner / policy code executing too early (SecurityException from DevicePolicyManager) on this device/OS.
4) Crash is triggered by a bad migration / corrupted local storage (SharedPreferences/DataStore type mismatch) during startup managers initialization.
5) Crash is triggered by network/bootstrap initialization (Firebase/Supabase) due to missing config, proguard, or strict mode (less likely).

## Evidence to collect
- adb logcat crash stacktrace (FATAL EXCEPTION) around launch timestamp
- package/component info (dumpsys package)
- last crash record (dumpsys activity / dropbox)

## Evidence (collected)
- Root cause from dropbox stacktrace:
  - java.lang.IllegalStateException: Default FirebaseApp is not initialized in this process com.mupa.agent.argos
  - Crash location: MaintenanceOverlay.kt:68 (FirebaseDatabase.getInstance().getReference(".info/connected"))

## Fix (implemented)
- MaintenanceOverlay no longer crashes when Firebase default app is not initialized.
- Behavior: if Firebase isn't ready, it skips the `.info/connected` listener and treats backend as connected for the overlay decision.

## Repro steps
1) Clear logcat buffer
2) Start Agent main activity
3) Capture logcat + identify exception + component

## Notes
- Do not apply fixes until we have a stacktrace and confirmed root cause.
