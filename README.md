# Network Control Center Android

Unified Android network utility combining two independent local engines:

- **ByeDPI** — system traffic processing through Android `VpnService` + local SOCKS5/tun2socks.
- **TG WS Proxy** — local MTProto proxy for Telegram with WebSocket/Cloudflare transport.

The project is being developed as a new standalone application. It does not modify or submit pull requests to upstream projects.

## Planned architecture

```text
Android app
├── app UI / service coordinator
├── ByeDPI engine
│   ├── Android VpnService
│   ├── hev-socks5-tunnel
│   └── ByeDPI core
└── Telegram engine
    ├── local MTProto listener
    └── TG WS Proxy native core
```

Both engines are intended to work independently:

- ByeDPI OFF / TG WS OFF — normal connection
- ByeDPI ON / TG WS OFF — general traffic processing
- ByeDPI OFF / TG WS ON — Telegram only
- ByeDPI ON / TG WS ON — both engines enabled

## First milestones

1. Modern Android/Compose shell and unified service state.
2. Integrate the TG WS native engine as a standalone module.
3. Integrate a current ByeDPI core and tun2socks path.
4. Add safe routing so native engine sockets cannot loop back into the VPN.
5. Add Wi‑Fi/mobile network detection, IPv4/IPv6 awareness and per-network profiles.
6. Add diagnostics and logs for DNS/TCP/UDP/QUIC/Telegram connectivity.

## Upstream projects

- https://github.com/dovecoteescapee/ByeDPIAndroid
- https://github.com/hufrea/byedpi
- https://github.com/heiher/hev-socks5-tunnel
- https://github.com/L33kr/tg-ws-proxy-android

## Status

Early development.
