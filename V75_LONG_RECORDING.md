# V75 — LONG RECORDING SAFETY

Based on V74 Performance.

- Verifies the MediaStore video URI after CameraX Finalize.
- Requires the finalized video item to report a size greater than 0 bytes.
- Removes a finalized-but-invalid video item instead of leaving a broken Gallery entry.
- Keeps V72/V73 storage and Gallery guards and V74 performance/lifecycle behavior.
- Does not change camera selection, zoom, photo capture, UI, or recording controls.

Test target: 5–10 minute recording, stop/finalize, playback, then another recording.
