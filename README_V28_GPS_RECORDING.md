# JejakCam V28 — GPS Recording

V28 mempertahankan seluruh alur dan fitur V27, lalu menambahkan GPS track saat rekaman video.

## Fitur baru
- GPS tetap OFF saat aplikasi dibuka.
- Tekan GPS pada HUD untuk mengaktifkan lokasi.
- Saat video direkam dan GPS ON, titik lokasi dicatat setiap update GPS.
- HUD menampilkan MAX speed, AVG speed, dan jarak tempuh.
- Setelah rekaman selesai, track GPS disimpan sebagai CSV di `Download/JejakCam/GPS`.
- CSV berisi waktu, latitude, longitude, speed, akurasi, dan bearing.
- Statistik jarak memakai perpindahan antar titik GPS yang cukup akurat.

## Privasi
- Tidak ada permintaan lokasi saat aplikasi dibuka.
- GPS hanya aktif setelah tombol GPS ditekan dan izin lokasi diberikan.
- V28 menggunakan ACCESS_COARSE_LOCATION.

## Alur kamera
Buka aplikasi → BUKA KAMERA → pilih VIDEO/FOTO → kamera aktif → REC.
