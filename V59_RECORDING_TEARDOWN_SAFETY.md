# V59 — Recording Teardown Safety

- Protects against late CameraX `Finalize` callbacks after Activity teardown.
- Prevents loop recording from starting a new segment while the Activity is stopping/destroyed.
- Clears timer/countdown/loop state before Activity destruction.
- Detaches the active Recording reference before requesting stop, avoiding duplicate stop paths.
- Version: 1.45.0 / versionCode 44.
