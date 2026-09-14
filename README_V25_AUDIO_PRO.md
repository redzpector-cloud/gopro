# JejakCam V25 — Audio Pro

Berdasarkan V24 Stabilization + Horizon FIX.

## Alur kamera
- Buka aplikasi: kamera OFF.
- BUKA KAMERA: hanya membuka layar action-cam.
- VIDEO/FOTO: baru menyalakan kamera.
- REC: mulai/berhenti rekaman.

## Audio
- MIC ON: video direkam dengan audio jika izin mikrofon tersedia.
- MIC OFF: video direkam tanpa audio dan tidak meminta izin mikrofon ketika memilih VIDEO.
- Pengaturan MIC tidak dapat diubah saat sedang merekam.
- Tombol sebelumnya bernama MIC ENH/MIC RAW disederhanakan karena CameraX Recorder tidak menyediakan akses langsung untuk menerapkan filter wind-noise custom pada track audio hasil rekaman.
- Dukungan noise suppression hardware Android tetap dideteksi, tetapi tidak dipaksakan ke pipeline CameraX agar tidak menyebabkan konflik dengan mic recorder.

## Stabilization
- Hardware/device video stabilization bila tersedia.
- Gyro/Horizon HUD tetap.
- Ini bukan post-processing horizon lock frame-by-frame.
