# JejakCam V24 — Stabilization + Horizon

Perubahan dari V23:
- Kamera tetap OFF saat aplikasi dibuka.
- BUKA KAMERA hanya membuka layar action-cam; VIDEO/FOTO yang menyalakan kamera.
- Video menggunakan CameraX video stabilization jika didukung perangkat.
- Camera2 continuous video autofocus tetap aktif.
- HUD Roll/Pitch memakai low-pass filtering agar indikator lebih stabil saat HP bergerak/di kendaraan.
- Horizon HUD mengikuti kemiringan secara halus.
- Status `STAB HW` ditampilkan jika perangkat melaporkan video stabilization hardware; jika tidak, `STAB AUTO`.

Catatan: indikator Horizon di V24 adalah bantuan visual/HUD. Rotasi frame video yang sebenarnya memerlukan pemrosesan video gyro/post-processing dan belum diklaim sebagai frame-level horizon lock.
