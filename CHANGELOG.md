# Changelog

## [v1.0 — 2026-08-14] — Anti force close + laporan crash otomatis

- Semua titik rawan dibungkus try/catch agar tidak force close: callback hasil
  dialog izin, `getMediaProjection`, dan callback encoder (thread MediaCodec).
- `CrashCatcher` baru (sender & receiver): menangkap exception tak terduga,
  menulis stack trace ke Realtime Log + file, lalu memuatnya ke log lagi saat
  app dibuka berikutnya — penyebab force close selalu terlihat di Realtime Log.

## [v1.0 — 2026-08-14] — Fix streaming tidak jalan (projection hilang)

- MediaProjection kini dibuat langsung di MainActivity (konsen dialog masih
  segar) lalu diserahkan ke foreground service via static bridge — tidak lagi
  lewat `putExtra`/`getParcelableExtra` yang bisa membuat token projection
  hilang saat re-parcel (gejala: log "Streaming dimulai" tapi tidak ada apa-apa).
- Service mencatat log tiap langkah (FGS aktif, projection diterima/kosong,
  error lengkap) — semua kegagalan kini terlihat di Realtime Log.
- Status sender menampilkan "Mencari receiver…" saat broadcast discovery jalan.

## [v1.0 — 2026-08-14] — Fix force close + dukungan Android 14

- Perbaikan force close saat memulai sender: pembukaan UDP port kini dibungkus
  try/catch — kalau port `45555` sudah dipakai (mis. receiver jalan di HP yang
  sama), muncul status error, bukan crash.
- Foreground service `mediaProjection` (`CastService`): wajib sejak Android 14
  (targetSdk 36) sebelum `MediaProjection.createVirtualDisplay` dipanggil.
- Streaming kini berjalan di background service + notifikasi dengan tombol
  Stop; tombol di activity hanya memulai/menghentikan service.

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
