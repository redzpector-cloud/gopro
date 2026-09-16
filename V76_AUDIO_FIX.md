# V76 — AUDIO RECORDING FIX

Based on V75 Long Recording.

## Fixes
- Checks RECORD_AUDIO permission before starting video recording.
- Explicitly attaches CameraX audio via `PendingRecording.withAudioEnabled()` for every recording when Mic/Audio is ON and permission is granted.
- No silent fallback when audio setup fails; the user receives an error instead.
- Existing V72–V75 storage, finalization, gallery, performance, compatibility, and long-recording guards are retained.

## Test
1. Set Mic/Audio to ON.
2. Start video recording.
3. Speak near the phone for 10–20 seconds.
4. Stop and play the video in Gallery.
5. Confirm voice is audible.
6. Repeat on the second phone that previously had camera compatibility issues.
