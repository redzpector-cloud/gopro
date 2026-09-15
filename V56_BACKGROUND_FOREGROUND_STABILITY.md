# V56 — Background / Foreground Camera Stability

- Remembers when the user intentionally opened the camera.
- Releases an idle CameraX binding when the Activity goes to the background.
- Invalidates queued CameraX callbacks before release.
- Automatically restores the camera when the Activity returns to the foreground.
- Does not tear down an active recording from `onStop()`.
- Closing the camera explicitly cancels automatic restoration.
- Version: 1.42.0 (versionCode 41)

Test flow: Buka Kamera → tekan Home/minimize → kembali ke app → preview should return automatically → switch screen again → close camera explicitly.
