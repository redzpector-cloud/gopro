# V63 — Storage Preflight Guard

Perubahan dari V62:
- Sebelum setiap segment baru dimulai, aplikasi memeriksa ruang kosong.
- Rekaman baru ditolak jika ruang kosong <= 256 MB atau <= 1%.
- Ini mencegah MediaStore membuat output video ketika ruang sudah terlalu kritis.
- Guard V62 saat rekaman berlangsung tetap dipertahankan.
- Tidak mengubah fitur kamera, HUD, GPS, loop, zoom, stabilisasi, audio, dan settings.

Versi: 1.49.0 / versionCode 48
