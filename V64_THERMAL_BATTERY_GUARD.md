# V64 — Thermal & Battery Guard

- Versi aplikasi: 1.50.0 / versionCode 49.
- Memantau suhu perangkat setiap 5 detik saat sedang merekam.
- Pada Android 10+ (API 29+), status thermal SEVERE atau lebih tinggi menghentikan rekaman secara aman.
- Baterai 0–4% menghentikan rekaman secara aman untuk mencegah shutdown mendadak.
- Timer LOOP dibatalkan sebelum penghentian.
- Guard tidak boleh menyebabkan crash jika informasi thermal/baterai tidak tersedia.
- Guard di-reset ketika sesi rekaman baru benar-benar dimulai.
- Fitur V63 dan seluruh fitur kamera sebelumnya dipertahankan.
