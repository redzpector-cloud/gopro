# V79 — Photo Mode Isolation

- Mode FOTO sekarang bind CameraX hanya dengan Preview + ImageCapture.
- VideoCapture/Recorder tidak lagi ikut dibind saat mode FOTO.
- Ini menghindari konflik kombinasi use case pada sebagian HAL/vendor kamera.
- Tombol foto tidak lagi mensyaratkan Recorder tersedia.
- Tambahan guard saat cache file foto tidak bisa dibuat.
- Mode VIDEO tetap menggunakan Preview + VideoCapture + ImageCapture.
- versionName: 1.61.0
- versionCode: 60
