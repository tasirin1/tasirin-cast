# Changelog

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
