# V53 — JejakCam UI & Settings

- Kamera utama dibuat lebih bersih; indikator tidak wajib tampil semua.
- Fitur utama memiliki status `AUTO / ON / OFF`.
- Indikator memiliki `TAMPIL / HIDE`. HIDE hanya menyembunyikan indikator, fungsi tetap berjalan.
- Pengaturan dapat dibuka dari Home atau tombol ⚙ di kamera.
- Tersedia preset SIMPLE, TEKNISI, LENGKAP dan RESET KE DEFAULT.
- Pengaturan tampilan disimpan di SharedPreferences.
- Stabilizer video mengikuti status ON/OFF yang dipilih.


## V53.1 build fix

V53.1 removes the unsupported `Preview.Builder#setPreviewStabilizationEnabled` call.
CameraX stabilization remains configured on the `VideoCapture` use case with a safe fallback.
