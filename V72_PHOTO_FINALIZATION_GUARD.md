# V72 — Photo Finalization Guard

- Continues V71 storage preflight and V68 MediaStore cleanup.
- On Android 10+, publishes the exact saved URI after capture.
- If publishing fails, removes the exact URI to avoid a stuck pending item.
- Keeps the photo capture guard and existing camera/video features unchanged.
- Version: 1.58.0 / versionCode 57.
