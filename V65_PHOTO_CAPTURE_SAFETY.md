# V65 — Photo Capture Safety

- Versi aplikasi: 1.51.0 / versionCode 50.
- Mencegah tap tombol FOTO berulang-ulang membuat beberapa capture berjalan bersamaan.
- Capture ditolak saat Activity sedang teardown atau kamera tidak aktif.
- Nama file foto diberi suffix milidetik untuk mengurangi risiko nama sama pada capture cepat.
- Guard di-reset setelah foto berhasil atau gagal.
- Fitur V64 dan seluruh fitur kamera sebelumnya dipertahankan.
