# JejakCam V45 — Smart Camera Tuning

Tujuan: memilih konfigurasi kamera yang paling aman dan berkualitas berdasarkan capability perangkat dan kondisi penggunaan.

Mode:
- SMART AUTO — default.
- QUALITY — prioritaskan detail.
- ACTION — prioritaskan gerakan/FPS.
- LOW LIGHT — prioritaskan exposure dan noise.

Logika:
- Deteksi kombinasi resolusi/FPS yang didukung kamera.
- Pilih 4K/30 bila tersedia untuk QUALITY/SMART.
- Pilih 1080p/60 bila tersedia untuk ACTION dan kondisi gerak.
- Kondisi gelap mengutamakan frame rate yang stabil dan exposure yang aman.
- Bitrate dipilih berdasarkan encoder/capability, bukan dipaksakan.
- Stabilization dipilih sesuai capability perangkat.
- HDR/AE-AF Lock tetap kompatibel.
- Jika konfigurasi ideal tidak tersedia, fallback ke konfigurasi terdekat yang stabil.
- Tidak mengubah setting secara agresif ketika sedang REC agar video tidak mengalami perubahan mendadak.

Prioritas:
1. Tidak crash.
2. Capability kamera selalu dihormati.
3. Frame rate stabil.
4. Exposure stabil.
5. Kualitas/detail maksimal sesuai perangkat.
