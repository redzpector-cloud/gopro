# JejakCam V51 — Video Quality Pro

Fokus utama: kualitas gambar video sebelum menambah efek bokeh.

Target:
- Preset kualitas: QUALITY / HIGH / MAX (sesuai kemampuan perangkat).
- Bitrate video diprioritaskan lebih tinggi ketika storage dan encoder mendukung.
- Resolusi dan FPS mengikuti capability kamera; jangan memaksa kombinasi yang tidak didukung.
- 4K/30 menjadi target utama bila kamera mendukung.
- 1080p/60 menjadi alternatif untuk gerakan lebih cepat bila didukung.
- Stabilization/Horizon tetap aktif sesuai capability.
- AE/AF Lock dan HDR tetap kompatibel.
- Low Light/Night tetap tersedia.
- Jika bitrate/resolusi tertentu tidak tersedia, fallback otomatis ke profile kamera yang paling dekat.
- Tidak crash karena capability kamera berbeda antar HP.

Prioritas kualitas:
1. Detail/sharpness
2. Stabilitas frame rate
3. Exposure konsisten
4. Bitrate cukup tinggi
5. Ukuran file tetap masuk akal
