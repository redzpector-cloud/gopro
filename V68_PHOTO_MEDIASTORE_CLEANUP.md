# V68 — Photo MediaStore Cleanup

- Keeps the V67 CameraX MediaStore builder fix.
- Removes a MediaStore row if ImageCapture fails after CameraX created it.
- Cleanup matches the generated display name and JejakCam relative path.
- Successful captures still publish `IS_PENDING=0` only after `onImageSaved`.
- Version: 1.54.0 / versionCode 53.
