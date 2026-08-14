## [v1.0 — 2026-08-14] — Koreksi aspek layar di receiver (fix gepeng & geser)

- Sender mengirim ukuran layar aslinya ke receiver (paket kontrol
  SCREEN_INFO) setelah discovery — sekali, di awal stream.
- Receiver menampilkan video dengan transform yang meregangkan frame ke
  aspek layar sender lalu fit-center — encoder/decoder yang menjepit
  resolusi (mis. 720x1600 menjadi 720x1088) tidak lagi membuat gambar
  gepeng/bergeser; mode potret tampil utuh di tengah.

## [v1.0 — 2026-08-14] — Capture ikut aspek layar + buffer texture stabil

- Sender: resolusi capture mengikuti aspek layar asli — mode potret kini
  menghasilkan video potret (tidak lagi diregangkan/bergeser ke kanan);
  preset kualitas berarti sisi terpanjang maksimal (640/854/1280/1920p).
- Receiver: ukuran buffer SurfaceTexture dijamin selebar video asli walau
  layout berubah (immersive/system bar) — video fit-center stabil, tidak
  lagi ke-zoom-in atau tidak tengah.

## [v1.0 — 2026-08-14] — Video receiver fit-center + pengaturan kualitas di sender

- Receiver: buffer SurfaceTexture kini disetel selebar video asli
  (setDefaultBufferSize) sebelum dirender — transform fit-center bekerja
  benar, video tidak lagi tampil miring/tidak tengah/ke-zoom-in; ukuran
  mengikuti laporan decoder otomatis saat resolusi berubah.
- Sender: pengaturan kualitas video baru (Low 640x360, Medium 854x480,
  High 1280x720 default, Ultra 1920x1080) di layar utama — pilihan
  tersimpan otomatis dan dipakai saat streaming dimulai.

## [v1.0 — 2026-08-14] — Receiver fullscreen + aspek rasio saat streaming

- Saat streaming dimulai, receiver otomatis masuk mode fullscreen: panel
  kontrol, tombol Start, dan hint disembunyikan; hanya tombol Stop yang
  tampil di pojok atas; system bar ikut disembunyikan (immersive sticky).
- Video 1280x720 kini dirender fit-center via transform Matrix pada
  TextureView — tidak lagi meregang/"tertekan" ke atas saat aspek layar
  tidak 16:9.
- System bar dan panel kontrol dikembalikan otomatis saat streaming berhenti.

# Changelog

## [v1.0 — 2026-08-14] — Receiver: TextureView (render andal di TV box)

- Ganti SurfaceView dengan TextureView di receiver — frame sudah terbukti
  dirender (`Frame dirender`) tapi tidak tampil; TextureView merender sebagai
  view biasa sehingga tidak ada masalah z-order/surface terpisah di TV box.
- Lifecycle surface dikelola (`SurfaceTextureListener`): surface dibuat ulang
  → receiver restart dengan surface baru; surface hancur → receiver berhenti.
- Log `Decoder output: <w>x<h>` saat format decoder keluar.

## [v1.0 — 2026-08-14] — Scan VirusTotal di CI

- Workflow kini meng-upload kedua APK rilis ke VirusTotal (hash lookup dulu,
  upload kalau belum pernah discan) lalu mencetak hasil deteksi — bukti
  false-positive antivirus. Secret baru: `VT_API_KEY`.

## [v1.0 — 2026-08-14] — Log receiver tampil di sender + tombol Copy Log

- Receiver meneruskan setiap baris lognya ke sender via UDP (prefix `TCLG`);
  sender menampilkannya dengan prefix `R:` di Realtime Log — debugging tanpa
  perlu buka app receiver.
- Tombol **Copy Log** di layar utama sender: salin seluruh log (sender +
  receiver) ke clipboard sekaligus.

## [v1.0 — 2026-08-14] — SPS/PPS in-band di setiap keyframe

- Sender menyimpan SPS/PPS lalu menempelkannya ke setiap keyframe (in-band)
  — decoder yang mengabaikan buffer config terpisah tetap bisa mulai decode.
- Keyframe request saat loss otomatis membawa SPS/PPS lagi (self-healing).

## [v1.0 — 2026-08-14] — Diagnosa receiver + perbaikan frame terbuang

- Receiver mencatat paket yang ditolak (magic/versi protokol) — kalau receiver
  masih build lama, semua paket versi 3 ditolak dan kini terlihat di log.
- Log tahap decoder: `Frame masuk decoder` dan `Frame dirender` — menunjukkan
  tepat di mana alur macet.
- Perbaikan: frame tidak lagi dibuang saat input buffer decoder penuh (ditunggu
  sampai buffer tersedia).

## [v1.0 — 2026-08-14] — Fix receiver layar hitam: SPS/PPS (codec config) dikirim

- Penyebab: encoder Android mengirim SPS/PPS sebagai buffer terpisah
  (`BUFFER_FLAG_CODEC_CONFIG`) yang sebelumnya dibuang sender → decoder
  receiver tidak bisa memulai decode sama sekali (tidak ada tampilan).
- Protokol v3: flag baru `FLAG_CODEC_CONFIG` di header; sender mengirim
  konfigurasi sebagai frame bertanda, receiver memberikannya ke decoder
  dengan flag `BUFFER_FLAG_CODEC_CONFIG`.
- Unit test baru: round-trip flag codec config + packetizer menandai
  chunk pertama.

## [v1.0 — 2026-08-14] — Fix MediaProjection callback wajib (Android 14)

- Android 14 (targetSdk 34+) mewajibkan `MediaProjection.registerCallback()`
  sebelum `createVirtualDisplay()` — tanpa itu `IllegalStateException:
  Must register a callback before starting capture`.
- Callback `onStop` menghentikan streaming bila sistem mematikan projection.

## [v1.0 — 2026-08-14] — Encoder: fallback konfigurasi + codec baru per percobaan

- Perbaikan retry: sebelumnya codec yang sudah di-release dipakai ulang
  ("codec is released already") — kini codec baru dibuat untuk tiap percobaan.
- Urutan fallback konfigurasi: CBR -> VBR -> CQ -> default -> tanpa
  `MAX_B_FRAMES` (mode CQ sering ditolak perangkat tertentu).

## [v1.0 — 2026-08-14] — Encoder stabil (drain sinkron + fallback bitrate)

- Ganti `MediaCodec.Callback` (rawan `CodecException` kosong di perangkat
  tertentu + butuh Looper) dengan drain sinkron `dequeueOutputBuffer` di thread
  pengirim.
- Fallback mode bitrate: CQ -> VBR -> CBR; kalau semua gagal, tercatat jelas.
- Log error encoder kini menyertakan kelas exception; guard encoder H.264 tidak
  tersedia.

## [v1.0 — 2026-08-14] — Fix MediaProjection gagal (wajib FGS mediaProjection)

- Sesuai AOSP (API 29+): `MediaProjectionManager.getMediaProjection()` harus
  dipanggil dari foreground service bertipe `mediaProjection`; sebelumnya
  dipanggil dari activity sehingga melempar SecurityException (gejala: log
  "Gagal membuat MediaProjection (null/exception)").
- Alur diperbaiki ke pola kanonik: activity meneruskan konsen (resultCode +
  resultData) ke `CastService` → service `startForeground` (FGS aktif) →
  `getMediaProjection` → `ScreenStreamer`.
- Perbaikan bug `resultCode > 0`: `RESULT_OK` bernilai -1, dicek ulang pakai
  `Activity.RESULT_OK` (penyebab build 100012 diam tanpa streaming).
- `ScreenStreamer.start()` mengembalikan Boolean — kalau port gagal dibuka,
  service berhenti bersih dan isStreaming tidak pernah aktif.

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
