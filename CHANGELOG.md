# Changelog

## [v1.0 — 2026-08-14] — Streaming H.264 via UDP

- Implementasi streaming penuh: sender (MediaProjection → VirtualDisplay →
  encoder H.264 CQ 30fps → packetizer UDP) dan receiver (UDP → jitter buffer →
  frame assembler → decoder H.264 → SurfaceView).
- Protokol v2: header 12 byte (magic, versi, flags, seq, timestamp, ukuran),
  port discovery `45556`, kontrol keyframe request (`TC` + cmd) di port video.
- Discovery otomatis (broadcast `TC-HI` / ACK `TC-OK`) + fallback input IP manual
  di sender; receiver membalas ACK dan mengirim alamatnya.
- Anti-loss: receiver deteksi gap urutan paket → minta keyframe balik ke sender.
- Log realtime di semua momen streaming (receiver ditemukan, streaming mulai,
  frame terkirim/diterima, gap/loss, error, berhenti).
- Unit test protokol: header, packetizer, frame assembler, jitter buffer.

## [v1.0 — 2026-08-14] — Log realtime

- Log realtime sender & receiver (pola LogActivity di tasirin-download-manager):
  auto-scroll, pencarian + highlight ERROR/FAILED, copy, clear, ekspor TXT.
- `protocol`: `RealtimeLog` (buffer aman multi-thread) + `CastLog` (per-proses).

## [v1.0 — 2026-08-14] — Rilis pertama via CI

- Scaffold monorepo: module `app-sender`, `app-receiver`, `protocol`.
- Konfigurasi Gradle mengikuti tasirin-download-manager: AGP 9.3.1, Gradle 9.7,
  minSdk 21, targetSdk 36, desugaring, lint abortOnError.
- CI `build.yml`: bump versionCode → lint/test → `assembleRelease` (R8 +
  signing keystore via secrets) → publish GitHub Release berisi kedua APK.
- Protokol UDP awal di `protocol`: konstanta, header paket, jitter buffer + unit test.
