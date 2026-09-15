# JejakCam V55 — Lens/Rebind Stability

Perubahan utama:
- Permintaan preset lensa/zoom disimpan saat CameraX sedang rebind.
- Setelah kamera berhasil bind, preset otomatis diterapkan.
- Callback CameraX diberi guard token agar callback lama tidak mengambil alih kamera baru.
- Status rebind dilacak agar operasi kamera tidak dianggap sudah siap sebelum bind selesai.
- Error saat mendapatkan ProcessCameraProvider ditangani dengan pesan yang jelas.
- Version: 1.41.0 / versionCode 40.
