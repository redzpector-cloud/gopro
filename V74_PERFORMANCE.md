# JejakCam V74 — Performance Guard

V74 keeps V72/V73 safety behavior and reduces unnecessary background/UI work.

- Storage monitor: 5 s interval and active only while camera screen is active.
- Thermal/battery monitor: 10 s interval and active only while camera screen is active.
- Recording timer HUD: 1 s update (display is second-based).
- Rotation/gyro sensors use SENSOR_DELAY_UI instead of GAME.
- Compass/horizon HUD is throttled to about 10 updates/second.
- Monitors are stopped when idle camera is released/backgrounded/closed.
- No changes to capture, MediaStore finalization, video/loop, zoom, or camera compatibility behavior.
