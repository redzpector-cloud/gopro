# JEJAK CAM V10 — Gyro Motor Stabil

V10 fokus pada perekaman motor:
- continuous video autofocus
- CameraX video stabilization jika perangkat mendukung
- Camera2 EIS request jika tersedia
- sensor rotation vector untuk horizon/level indicator
- gyroscope motion indicator (SMOOTH / MOVE / SHAKE)
- wide camera fallback
- mencegah pergantian kamera saat recording

Catatan: gyroscope pada V10 dipakai sebagai indikator gerakan. Koreksi frame penuh berbasis gyro membutuhkan pipeline video/frame transform khusus; V10 tidak berpura-pura melakukan koreksi tersebut. Stabilisasi rekaman tetap menggunakan EIS/OIS/hardware yang tersedia pada perangkat.
