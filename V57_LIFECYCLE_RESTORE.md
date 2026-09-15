# JejakCam V57 — Lifecycle & Restore Stability

V57 keeps the camera intent across Activity recreation/configuration changes while avoiding auto-opening the camera on a fresh launch.

## Changes
- Save `cameraRequested` and photo/video intent in `onSaveInstanceState`.
- Restore that intent in `onCreate`.
- `onResume()` reopens CameraX only when the previous session requested the camera.
- Keeps the existing V56 background release behavior.
- Does not attempt to serialize an active `Recording` object.
- Version: 1.43.0 / versionCode 42.

## Test
1. Open camera.
2. Rotate/recreate Activity if the device allows it, then verify camera returns.
3. Press Home, return to the app, verify camera returns.
4. Close camera explicitly, press Home, return; camera must stay closed.
5. Start recording, background the app, then return and verify the recording behavior on the device.
