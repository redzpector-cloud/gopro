# JejakCam V27 — GPS + Compass HUD

V27 mempertahankan fitur V26 dan menambahkan GPS serta kompas untuk HUD action-camera.

## Alur kamera
1. Buka aplikasi → kamera OFF.
2. Tekan **BUKA KAMERA** → masuk ke layar action-cam, hardware kamera masih OFF.
3. Tekan **VIDEO** atau **FOTO** → kamera baru aktif.
4. Tekan REC untuk mulai merekam.
5. Tekan X untuk menutup kamera dan kembali ke home.

## GPS
- GPS **OFF secara default**.
- GPS hanya aktif setelah tombol `GPS OFF` ditekan dan izin lokasi diberikan.
- Menggunakan izin lokasi **approximate/coarse**, bukan lokasi presisi.
- HUD menampilkan status GPS, kecepatan km/jam, dan akurasi bila tersedia.
- GPS dihentikan saat aplikasi masuk background agar hemat baterai dan menjaga privasi.

## Kompas
- Menggunakan rotation-vector sensor HP.
- Tidak membutuhkan izin lokasi.
- Menampilkan arah mata angin dan derajat di HUD.

## Fitur V26 yang tetap ada
- 1080P/4K
- stabilisasi/EIS bila didukung perangkat
- horizon + roll/pitch + gyro motion
- zoom 0.5/1/2/4x
- exposure EV
- timer 3/5/10 detik
- pause/resume
- loop recording 3 menit
- MIC ON/OFF
- battery HUD
