# JejakCam V58 — Recording Lifecycle Safety

V58 keeps the existing V57 camera restore behavior and adds protection around VideoCapture finalization.

## Changes
- Marks the Activity as stopping before `onStop()` work begins.
- Prevents a loop segment from starting after the Activity is stopping/destroyed.
- Cancels the loop-stop callback when a recording finalizes.
- Prevents `startSegment()` from starting when the camera is no longer active.
- Keeps explicit user stop behavior intact.
- Version: 1.44.0 / versionCode 43.

## Test
1. Open camera and start video.
2. Stop normally; verify it returns to READY and does not start another segment.
3. Enable LOOP and let one segment finalize; verify the next segment starts once.
4. While recording, press Home/lock the screen and return; verify no duplicate segment starts.
5. Close the app while recording; verify no new loop segment starts after shutdown.
