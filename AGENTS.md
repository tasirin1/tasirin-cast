# Panduan pengelolaan repo (untuk AI)

Baca file ini SEBELUM mengubah, memperbaiki, atau mengelola repository ini.
Panduan lengkap (arsitektur, cara pakai) ada di `README.md` — jaga sinkron.

## Struktur repository

```
.
├── .github/workflows/build.yml       # CI: bump versionCode → build 2 APK → lint/test → artifact
├── .github/PULL_REQUEST_TEMPLATE.md  # Template PR (wajib ringkasan + verifikasi)
├── app-sender/build.gradle.kts       # Transmitter: MediaProjection + MediaCodec + UDP
│   └── stream/ScreenStreamer.kt      # Capture → encode H.264 → packetizer → UDP
│   └── LogActivity.kt                # Log realtime + ekspor TXT
├── app-receiver/build.gradle.kts     # Receiver: UDP + jitter buffer + MediaCodec + SurfaceView
│   └── net/ScreenReceiver.kt         # UDP → jitter → assembler → decoder → Surface
│   └── LogActivity.kt                # Log realtime + ekspor TXT
├── protocol/build.gradle.kts         # Library bersama: framing UDP, header, jitter buffer
│   ├── RealtimeLog.kt                # Buffer log aman multi-thread
│   └── CastLog.kt                    # Log realtime per-proses app (object)
│   ├── Protocol.kt / PacketHeader.kt # Konstanta + header 12 byte bersama
│   ├── Packetizer.kt / FrameAssembler.kt  # Pecah & rakit frame jadi chunk UDP
│   └── JitterBuffer.kt               # Urutkan paket, buang duplikat/lama
├── CHANGELOG.md                       # Riwayat perubahan per rilis (update manual)
├── settings.gradle.kts               # rootProject "TasirinCast"; include 3 modul
└── gradle wrapper                    # build via ./gradlew (CI saja untuk rilis)
```

## Arsitektur ringkas

- **Sender** (`app-sender`): `MediaProjection` → `VirtualDisplay` + `InputSurface`
  → `MediaCodec` (H.264, tanpa B-frame, bitrate mode CQ) → packetizer UDP (≤1200 B).
- **Receiver** (`app-receiver`): UDP socket → `JitterBuffer` (urutkan seq,
  buang duplikat) → `FrameAssembler` (rakit frame utuh) → `MediaCodec` decoder
  (thread drain render) → `SurfaceView`.
- **Protokol** (`protocol`): semua konstanta & format paket di sini — sender dan
  receiver WAJIB sinkron byte-per-byte. Perubahan protokol = perubahan satu commit.
- **Anti-loss**: receiver mengirim paket *request keyframe* (IDR) saat ada paket
  hilang; JANGAN retransmit per paket untuk video.

## Aturan pengembangan

1. **Build resmi HANYA via GitHub Actions** — rilis = `assembleRelease` + publish
   GitHub Release (jangan pakai debug). Build lokal hanya untuk debugging cepat.
2. **Bahasa**:
   - **UI aplikasi memakai Bahasa Inggris** (default `values/strings.xml`).
   - **Komentar kode, dokumentasi internal, dan commit tetap Bahasa Indonesia**.
3. **Gaya commit**: `type(scope): deskripsi` — tipe: `feat`, `fix`, `ui`, `perf`,
   `refactor`, `docs`, `chore`. Satu commit satu tujuan.
4. **Jangan ubah `versionName`/`versionCode` manual** — `versionName` tetap `"1.0"`;
   `versionCode` di-bump otomatis oleh CI (`100000 + run_number`).
5. **Jaga kompatibilitas Android 5 (minSdk 21)**: API baru harus punya fallback;
   jangan naikkan minSdk tanpa diskusi.
6. **Jaringan jangan di main thread**; buffer/encode/decode wajib async.
7. **Jangan commit keystore / token / secret apa pun** (`*.jks`, `keystore.b64`
   sudah di `.gitignore`; token GitHub jangan pernah masuk file repo).
8. **PR**: workflow ikut build (tanpa release) — gunakan untuk mengecek
   compile/CI sebelum merge ke `main`.
9. **Lint & unit test wajib hijau** sebelum merge — `lintDebug` (abortOnError
   aktif) mengawal API >21 jangan sampai lolos; `testDebugUnitTest` menjaga
   logika murni (`protocol`: header paket, jitter buffer).
10. **Update dependensi (AGP/Kotlin/Gradle) bertahap** — jangan lompat beberapa
    versi sekaligus; tiap langkah lewat CI dulu.
11. **Changelog wajib per PR** — setiap PR menambah entri `CHANGELOG.md`
    (judul `## [v1.0 — tanggal] — ringkasan`) dan menyebut nomor PR pada isi
    entri setelah PR dibuat.
12. **Jangan berhenti di tengah alur rilis** — setelah PR merge, pantau build
    `main` sampai sukses.

## Cara memicu build & rilis

- Push ke `main` atau PR → GitHub Actions `Build APK` otomatis jalan.
- Push ke `main` + secret keystore tersedia → `assembleRelease` (R8) untuk
  `app-sender` & `app-receiver`, lalu **GitHub Release** `v<versionName>`
  berisi kedua APK bertanda tangan (pola sama tasirin-download-manager).
- Jangan ubah `versionName`/`versionCode` manual — CI yang bump (aturan 4).

## Secrets yang dibutuhkan (Settings → Secrets and variables → Actions)

| Secret | Isi |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore.jks` |
| `KEYSTORE_PASSWORD` | password store keystore |
| `KEY_ALIAS` | alias kunci |
| `KEY_PASSWORD` | password kunci |
