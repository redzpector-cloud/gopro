# V77 — Call Interruption Recovery

Based on V76 Audio Fix.

- Keeps the V76 microphone/audio recording path unchanged.
- Remembers an active recording across Android lifecycle interruption.
- If CameraX finalizes the interrupted recording, the old segment remains finalized and a fresh segment is started after the camera is available again.
- Does not reuse a stale `Recording` object.
- If the recording survives the interruption, no duplicate recording is started.
- This is lifecycle-based recovery; cellular/video-call behavior still depends on the Android device and whether the Activity receives lifecycle callbacks.
