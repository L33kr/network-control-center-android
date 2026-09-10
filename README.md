# DPI Control

DPI Control — standalone Android-приложение, объединяющее локальный ByeDPI VPN и Telegram WS Proxy в одном интерфейсе.

Проект развивается отдельно и не создаёт PR в исходные/upstream-репозитории.

## Что уже есть

### ByeDPI / Android VPN

Трафик Android проходит через:

```text
Android apps
   ↓
VpnService / TUN
   ↓
hev-socks5-tunnel
   ↓
local ByeDPI SOCKS5
   ↓
Internet
```

В приложении доступны:

- запуск/остановка ByeDPI через Android `VpnService`;
- актуальный ByeDPI core;
- `hev-socks5-tunnel` для маршрутизации TUN → SOCKS5;
- готовые профили ByeDPI;
- каталог готовых стратегий;
- ручная строка аргументов ByeDPI;
- Fake SNI (`{sni}` в поддерживаемых стратегиях);
- автотест стратегий по 10 контрольным хостам YouTube/Google Media/Discord;
- определение Wi-Fi / мобильной сети;
- управление IPv6;
- DNS-настройка.

### Доменные списки

Поддерживаются три режима:

- **Весь трафик** — стратегия применяется без ограничения по доменам;
- **Только указанные** — desync применяется только к заданным доменам;
- **Игнорировать указанные** — заданные домены проходят без desync, остальные используют выбранную стратегию.

Есть быстрые наборы для:

- YouTube;
- Discord;
- Google Media.

Whitelist основан на штатном `--hosts` ByeDPI. Режим исключений реализован через группы ByeDPI, так как отдельного `hostlist-exclude`, как в zapret/winws, у ByeDPI нет.

### Telegram WS Proxy

Встроен отдельный Telegram-прокси:

```text
Telegram
   ↓
127.0.0.1:<local port>
   ↓
TG WS native engine
   ↓
WSS / Cloudflare or direct Telegram DC
```

Приложение:

- запускает/останавливает foreground service;
- автоматически выбирает свободный локальный порт при конфликте;
- открывает Telegram с готовой `tg://proxy` конфигурацией;
- показывает native-ошибки запуска;
- сохраняет совместимость с версиями ядра без отдельных optional exports.

## Интерфейс

DPI Control использует тёмный Material 3 UI и собственную иконку.

Основные разделы:

- **Главная** — состояние VPN и текущая конфигурация;
- **Стратегии** — быстрые профили, каталог и ручные аргументы;
- **Домены** — include/exclude списки;
- **Telegram** — TG WS Proxy.

## Beta status

`v0.4.0-beta.1` фиксирует текущую рабочую базу перед более крупной переработкой профилей и наборов в стиле zapretgui.

Что уже проверено на реальном устройстве:

- приложение устанавливается и запускается;
- Android VPN service поднимается;
- Telegram WS Proxy запускается и работает.

Эффективность конкретной ByeDPI-стратегии зависит от оператора, сети и текущего DPI. Beta не гарантирует, что один и тот же preset будет одинаково работать у всех провайдеров.

## Сборка

Требования:

- Java 17;
- Gradle 8.9;
- Android SDK 35;
- Android NDK `27.0.12077973`;
- CMake `3.22.1`.

Сначала загрузите pinned native-зависимости.

Windows PowerShell:

```powershell
.\scripts\bootstrap-tgws.ps1
.\scripts\bootstrap-byedpi.ps1
gradle :app:assembleDebug
```

Linux/macOS:

```bash
./scripts/bootstrap-tgws.sh
./scripts/bootstrap-byedpi.sh
gradle :app:assembleDebug
```

GitHub Actions делает те же checkout автоматически.

## Pinned engines

- TG WS Android: `L33kr/tg-ws-proxy-android@94d0620aff9a9e0dd08a1f9688a904da09df497e`
- ByeDPI: `hufrea/byedpi@ba532298de7b28cfe854aea83d061369d13ca290`
- hev-socks5-tunnel: `heiher/hev-socks5-tunnel@941c758101385d145c66210ac88991daaf27d4b6`

## Routing note

Сам пакет DPI Control исключён из собственного `VpnService`, чтобы native ByeDPI и TG WS не попадали обратно в TUN и не создавали рекурсивный маршрут `VPN → proxy → VPN`.

## Upstream / references

- https://github.com/hufrea/byedpi
- https://github.com/heiher/hev-socks5-tunnel
- https://github.com/dovecoteescapee/ByeDPIAndroid
- https://github.com/romanvht/ByeByeDPI
- https://github.com/L33kr/tg-ws-proxy-android
- https://github.com/Flowseal/zapret-discord-youtube

## Дальше

После beta планируется перейти от одной глобальной стратегии к более гибкой модели:

`Набор → профили сервисов → доменные списки → стратегия/PASS`

Это позволит независимо настраивать YouTube, GoogleVideo/QUIC, Discord, пользовательские домены и исключения, не превращая приложение в тяжёлый фоновый сервис.
