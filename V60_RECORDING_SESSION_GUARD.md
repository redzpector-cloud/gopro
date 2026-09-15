# V60 — Recording Session Guard

- Version: 1.46.0 / versionCode 45.
- Adds a recording-session generation token so late CameraX callbacks from an older Recording cannot modify or restart the current session.
- Loop-stop callbacks are tied to the active recording session.
- Prevents duplicate startSegment() calls while a Recording/finalization is still active.
- Existing camera, HUD, GPS, loop, lens, stabilization and lifecycle features are preserved.
