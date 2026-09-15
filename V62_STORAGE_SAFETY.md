# V62 — Storage Safety Guard

- Monitors free storage every 3 seconds while recording.
- Stops recording safely at <=256 MB free or <=1% free.
- Cancels loop-stop callback before safety stop.
- Leaves a reserve for CameraX/MediaStore finalization.
- Version 1.48.0 / versionCode 47.
