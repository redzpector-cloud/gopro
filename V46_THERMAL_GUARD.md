# JejakCam V52 — Thermal Guard

Tujuan: menjaga kestabilan rekaman panjang, terutama 4K, saat perangkat mulai panas.

Fitur:
- 🌡️ Thermal status: NORMAL / WARM / HOT.
- Monitoring suhu/thermal status perangkat bila API menyediakan data.
- Peringatan sebelum kondisi panas menjadi kritis.
- Saat panas meningkat, Smart Camera Tuning dapat memilih profile yang lebih ringan pada sesi berikutnya.
- Jangan mengubah resolusi/FPS secara mendadak saat REC karena dapat mengganggu video.
- Jika thermal API tidak tersedia, gunakan fallback aman tanpa crash.
- Prioritaskan kestabilan frame dan keselamatan perangkat.
- Loop Recording, Smart Storage, Stabilization Pro, Low Light Pro, Audio Pro, HDR, AE/AF Lock dan preset V44 tetap dipertahankan.

Prinsip:
1. Tidak menghentikan rekaman secara tiba-tiba kecuali sistem/perangkat memaksa.
2. Beri peringatan lebih awal.
3. Hindari perubahan kualitas di tengah file.
4. Untuk sesi berikutnya, turunkan beban jika perangkat terlalu panas.
