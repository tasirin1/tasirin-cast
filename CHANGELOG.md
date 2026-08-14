# Changelog

## [v1.0 — 2026-08-14] — Scaffold awal repo cast

- Scaffold monorepo: module `app-sender`, `app-receiver`, `protocol`.
- Konfigurasi Gradle mengikuti tasirin-download-manager: AGP 9.3.1, Gradle 9.7,
  minSdk 21, targetSdk 36, desugaring, lint abortOnError.
- CI `build.yml`: build kedua APK + lint + unit test + upload artifact.
- Protokol UDP awal di `protocol`: konstanta, header paket, jitter buffer + unit test.
