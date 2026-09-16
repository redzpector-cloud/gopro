# V63 – Camera Stability

- Keep V62 compatibility-safe camera startup.
- Validate wide/ultra-wide selector with `hasCamera()` before binding.
- Automatically fall back to the normal rear camera when wide is unavailable.
- Guard provider acquisition and wide-camera rebinding against exceptions.
- No changes to the existing Foto/Video recording flow.
