# V62 Camera Compatibility Fix

Perbaikan fokus pada crash saat kamera dibuka di HP lain.

- Start camera memakai konfigurasi CameraX standar.
- Menghapus pemaksaan Camera2 CaptureRequest dari Preview/VideoCapture saat startup.
- Menghindari request autofocus/stabilization vendor-specific yang dapat ditolak HAL tertentu.
- Video quality tetap memakai QualitySelector dengan fallback.
- Video dan Photo use case dibuat dalam try/catch.
- Binding kamera memakai kamera belakang standar sebagai baseline kompatibilitas.
- Jika konfigurasi tidak didukung, aplikasi menampilkan pesan dan tidak crash.

Catatan: build harus dijalankan di environment Android/Gradle project. Environment percakapan ini tidak memiliki Gradle wrapper/Gradle command.
