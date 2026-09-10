# Network Control Center Android

Standalone Android application that combines two independent local network engines in one UI.

- **ByeDPI** — planned system traffic processing through Android `VpnService` + local SOCKS5/tun2socks.
- **TG WS Proxy** — integrated local MTProto proxy for Telegram with WebSocket/Cloudflare transport.

The project does not modify or submit pull requests to upstream projects.

## Current status

### Telegram WS — integrated

The first functional engine is connected to the application:

```text
Telegram
   ↓
127.0.0.1:1443
   ↓
TG WS native engine (Rust / libtgwsproxy.so)
   ↓
WSS / Cloudflare or direct Telegram DC
```

The main screen can start/stop the foreground proxy service and open Telegram with a generated `tg://proxy` configuration.

TG WS native libraries are currently pinned to:

`L33kr/tg-ws-proxy-android@94d0620aff9a9e0dd08a1f9688a904da09df497e`

They are kept outside this repository so the native engine can be updated independently.

### ByeDPI — next integration step

The UI/state boundary already exists, but the VPN engine is intentionally disabled until the current ByeDPI core, hev-socks5-tunnel and safe Android routing are connected.

## Architecture

```text
Network Control Center
├── Compose UI
├── UnifiedEngineController
├── TG WS
│   ├── TgWsProxyService
│   ├── TgWsController
│   ├── TgWsNative (JNA)
│   └── libtgwsproxy.so
└── ByeDPI (next)
    ├── Android VpnService
    ├── hev-socks5-tunnel
    └── current ByeDPI core
```

## Build

The project uses Java 17. TG WS native libraries need to be placed under `third_party/tg-ws-proxy-android` first.

Windows PowerShell:

```powershell
.\scripts\bootstrap-tgws.ps1
gradle :app:assembleDebug
```

Linux/macOS:

```bash
./scripts/bootstrap-tgws.sh
gradle :app:assembleDebug
```

GitHub Actions performs the same pinned dependency checkout automatically and uploads the debug APK as a workflow artifact.

## Upstream projects

- https://github.com/dovecoteescapee/ByeDPIAndroid
- https://github.com/hufrea/byedpi
- https://github.com/heiher/hev-socks5-tunnel
- https://github.com/L33kr/tg-ws-proxy-android

## Roadmap

1. ✅ Modern Android/Compose shell and unified service state.
2. ✅ TG WS native engine integration.
3. ⏳ Current ByeDPI core + hev-socks5-tunnel integration.
4. ⏳ Safe VPN/native socket routing without loops.
5. ⏳ Wi-Fi/mobile detection, IPv4/IPv6 auto mode and per-network profiles.
6. ⏳ DNS/TCP/UDP/QUIC/Telegram diagnostics and unified logs.
