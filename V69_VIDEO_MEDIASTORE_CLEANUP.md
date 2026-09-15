# V69 — Video MediaStore Cleanup

- Failed VideoRecordEvent.Finalize outputs are removed from MediaStore when CameraX returns a non-empty output URI.
- Prevents broken/partial video entries from remaining in the Gallery after a failed finalization.
- Keeps the existing recording/session/loop/storage/thermal guards.
- Version: 1.55.0 / versionCode 54.
