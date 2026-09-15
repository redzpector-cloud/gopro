# JejakCam V34 — Loop Recording

V34 upgrades the existing action-camera recorder with selectable loop segments.

## Loop cycle
- LOOP OFF
- LOOP 1m
- LOOP 3m
- LOOP 5m
- LOOP 10m
- back to LOOP OFF

The setting can only be changed while not recording. Each segment is saved as a separate MP4 under `Movies/JejakCam`. When a segment reaches its duration, the current recording is finalized and the next segment starts automatically.

## Existing behavior preserved
- Camera is OFF when the app opens.
- User taps `BUKA KAMERA` first.
- User explicitly starts recording with REC / QUICK REC.
- MIC, pause/resume, GPS track and existing HUD remain available.
- User STOP ends the loop and saves the final segment normally.
