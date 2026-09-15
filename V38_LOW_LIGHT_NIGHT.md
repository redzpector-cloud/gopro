# JejakCam V45 — Low Light / Night Video

Fokus V45:
- Mode LOW LIGHT / NIGHT pada UI kamera.
- Tombol mode dapat diaktifkan/nonaktifkan tanpa mengubah alur REC.
- Saat aktif, aplikasi diarahkan untuk memakai exposure compensation dan FPS yang lebih rendah bila didukung kamera.
- Hindari menaikkan ISO secara agresif; kemampuan final bergantung pada Camera2/HAL perangkat.
- AE/AF Lock V45 tetap dipertahankan.
- Loop Recording, Quick REC, Smart Storage, EIS/Horizon/Gyro/GPS tetap dipertahankan.

Catatan implementasi:
Mode Night harus menggunakan capability kamera yang tersedia pada perangkat. Jika manual ISO/shutter tidak didukung,
aplikasi tetap berjalan dengan mode auto dan tidak boleh crash.
