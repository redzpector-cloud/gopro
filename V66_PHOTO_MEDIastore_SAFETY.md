# V66 — Photo MediaStore Safety

- Photo capture now creates its MediaStore row before CameraX writes.
- Android 10+ uses `IS_PENDING` while the JPEG is being written.
- Successful captures are published after completion.
- Failed captures remove the incomplete MediaStore row.
- Prevents empty/half-written gallery entries after a failed photo.
- Version: 1.52.0 / versionCode 51.
