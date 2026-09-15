# V54 — Camera Stability & Lifecycle

Perubahan V54:
- Menambahkan token/generasi start CameraX agar callback kamera lama tidak dapat membuka kembali kamera setelah ditutup atau direkonfigurasi.
- Mencegah callback CameraX melanjutkan proses ketika Activity sedang finishing/destroyed.
- Men-invalidasi callback kamera saat `closeCamera()` dan `onDestroy()`.
- Menjaga fitur kamera, loop recording, GPS, HUD, dan pengaturan V53 tetap digunakan.
- Versi aplikasi: `1.40.0` / versionCode `39`.

Tujuan: mengurangi race condition saat buka/tutup kamera, ganti kualitas/lensa, dan lifecycle Activity.
