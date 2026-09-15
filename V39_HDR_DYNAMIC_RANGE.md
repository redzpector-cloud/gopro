# JejakCam V42 — HDR / Dynamic Range

Tujuan:
- Mode HDR / Dynamic Range untuk adegan dengan jendela/lampu terang dan area bengkel gelap.
- Tombol HDR AUTO / ON / OFF pada UI kamera.
- AUTO menjadi default agar pengguna tidak perlu mengatur terus-menerus.
- Jangan memaksa HDR jika capability kamera tidak tersedia.
- Jika HDR tidak didukung, fallback ke normal Auto Exposure tanpa crash.
- AE/AF Lock V37 tetap kompatibel.
- Low Light/Night V42 tetap tersedia.
- Loop Recording, Quick REC, Smart Storage, EIS/Horizon/Gyro dan GPS tetap dipertahankan.

Prioritas:
1. Stabil dan tidak crash.
2. Exposure tetap natural.
3. Hindari perubahan brightness mendadak saat REC.
4. HDR harus kompatibel dengan kemampuan Camera2/HAL perangkat.
