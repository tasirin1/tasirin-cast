<p align="center">
  <b>Tasirin Cast — Android</b><br>
  Receiver & transmitter layar minimal delay untuk Android 5.0+ (API 21+).
</p>

# Tasirin Cast (Android)

[![Build](https://github.com/tasirin1/tasirin-cast/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/tasirin-cast/actions)

**Dua aplikasi + satu protokol bersama:** `app-sender` (transmitter layar) dan
`app-receiver` (penerima), terhubung via UDP dengan protokol ringan buatan sendiri.
Target latensi **80–150 ms** di jaringan Wi-Fi yang sama — terasa real-time.

Dibangun dengan **Kotlin + Jetpack**, tanpa iklan, tanpa akun, kode terbuka di GitHub.

> **Untuk AI yang mengelola repo ini: baca [AGENTS.md](AGENTS.md) dulu** — berisi
> struktur, arsitektur, aturan pengembangan, dan alur build/release.

## Daftar isi

- [Arsitektur](#arsitektur)
- [Modul](#modul)
- [Protokol](#protokol)
- [Peta jalan](#peta-jalan)
- [Build](#build)
- [Lisensi](#lisensi)

## Arsitektur

```
HP pengirim (app-sender)                    HP penerima (app-receiver)
┌──────────────────────────┐                 ┌──────────────────────────┐
│ MediaProjection          │   UDP unicast   │ UDP socket               │
│   ↓ VirtualDisplay       │ ──────────────► │   ↓ jitter buffer        │
│   ↓ MediaCodec H.264     │                 │   ↓ MediaCodec H.264     │
│   ↓ packetizer (1.2 KB)  │  ◄─ keyframe    │   ↓ SurfaceView          │
└──────────────────────────┘   request      └──────────────────────────┘
        module :protocol (framing UDP, header seq/keyframe) dipakai dua-duanya
```

- **Sender**: tangkap layar via `MediaProjection` → encode H.264 (tanpa B-frame,
  bitrate mode CQ) → potong paket UDP kecil → kirim. Sejak Android 14 memakai
  foreground service `mediaProjection` (wajib untuk targetSdk 34+), jadi streaming
  tetap jalan saat app di background.
- **Receiver**: terima UDP → urutkan di jitter buffer → decode → render langsung
  ke `SurfaceView`.
- **Anti-loss**: receiver kirim paket *request keyframe* balik ke sender saat ada
  paket hilang (bukan retransmit per paket).

## Modul

| Modul | Tipe | Peran |
|---|---|---|
| `app-sender` | APK | Transmitter layar (MediaProjection + MediaCodec + UDP) |
| `app-receiver` | APK | Receiver (UDP + MediaCodec + SurfaceView) |
| `protocol` | Library | Framing UDP, header paket, jitter buffer, konstanta bersama |

## Protokol

- Transport: **UDP unicast**, port default `45555` (konstanta `Protocol.DEFAULT_PORT`).
- Header 12 byte: magic `"TC"` + versi + flags (frame-start) + sequence
  (wrap-around) + timestamp (ms) + ukuran payload.
- Paket ≤ 1200 byte (di bawah MTU, hindari fragmentasi).
- Discovery: broadcast `TC-HI` di port `45556` → receiver balas `TC-OK`.
- Anti-loss: receiver minta keyframe balik saat ada paket hilang.

## Peta jalan

- [x] Scaffold monorepo (sender + receiver + protocol)
- [x] Sender: MediaProjection + encoder H.264 via Surface
- [x] Receiver: jitter buffer + decoder H.264 + SurfaceView
- [x] Discovery otomatis (UDP broadcast) + fallback input IP manual
- [x] Keyframe request saat loss
- [ ] Audio (mic / aplikasi sendiri — catatan: capture audio internal butuh API 29+)
- [ ] Mode tanpa router (hotspot receiver / Wi-Fi Direct)

## Build & rilis

Build & rilis resmi HANYA via GitHub Actions (push ke `main`) — setiap push
membangun `assembleRelease` (R8 + tanda tangan keystore) dan publish
[GitHub Release](https://github.com/tasirin1/tasirin-cast/releases) berisi
APK sender & receiver.

## Lisensi

[GPL-3.0](LICENSE) © 2026 tasirin
