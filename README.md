<p align="center"><img src="HotShare/branding/wordmark.png" alt="HotShare" width="420"></p>

# HotShare

Share one phone's internet connection with **your other devices** — phones, laptops, any OS.
No root, no account, one sideloaded APK.

| | |
|---|---|
| **How it works** | The host phone runs an HTTP (`:8080`) + SOCKS5 (`:1080`) proxy bound to its hotspot IP. Clients (Android / macOS / any OS) route their traffic through it, so every connection is **re-originated by the host** — the ISP only ever sees the host's own TTL-64 traffic. |
| **Client modes** | • System/manual proxy (browsers + most apps)<br>• Built-in **tun2socks VPN** (hev-socks5-tunnel) — all apps, TCP + UDP/QUIC, token-authed |
| **Hotspot types** | **Temporary (app)** LocalOnlyHotspot · **Mobile hotspot (system)** · Auto — both 2.4 GHz and 5 GHz |
| **Monitoring** | Foreground-service notifications with live ↓/↑ speed, uptime, client count; in-app speed meter + sparkline; per-session QR token |

## Repo layout
- [`HotShare/`](HotShare/) — the Android app ([README](HotShare/README-SIDELOAD.md) · [build & install](HotShare/scripts/) · [test report](HotShare/dist/TEST-REPORT.md))
- [`ISP-Hotspot-Sharing-Manual.md`](ISP-Hotspot-Sharing-Manual.md) — the underlying manual: why tethering gets blocked (MAC binding, TTL, IPv6/DPI) and every bypass (proxy, TTL 65, root, travel router)
- Root PNGs + [`HotShare/branding/`](HotShare/branding/) — brand art; regenerate app icons with `python3 HotShare/scripts/gen-icons.py`

## Quick start
1. Build (or grab the APK from Releases): `bash HotShare/scripts/build-apk.sh`
2. Install on both phones: `bash HotShare/scripts/install-both.sh HotShare/dist/HotShare-v1.apk`
3. **Host:** open HotShare → *Share internet* → show the QR
4. **Client:** open HotShare → *Scan QR* → *Connect* (or set Wi-Fi proxy to `<host-ip>:8080`)

> Fair use of your own paid quota on your own devices; prefer official multi-device add-ons where available.

## Status
v1.5 — verified end-to-end on real devices (Samsung / OnePlus / TECNO clients, Android 13–16) and on a live ISP network. See [`HotShare/dist/TEST-REPORT.md`](HotShare/dist/TEST-REPORT.md).

## Legal & acceptable use
- **License:** MIT — see [`LICENSE`](LICENSE). Third-party components (hev-socks5-tunnel/MIT, ZXing, AndroidX, Kotlin/Apache-2.0) are credited in [`THIRD-PARTY_NOTICES.md`](THIRD-PARTY_NOTICES.md); the app shows the same notice in-app.
- **What this is:** general-purpose networking software — a hotspot sharing tool with a proxy and a tun2socks client. Publishing such tools is legal; comparable apps (Every Proxy, VPN Hotspot, PdaNet, Orbot) have been distributed openly for years.
- **Your responsibility:** use it only on connections you are authorized to share, with devices you own, in accordance with your provider's terms of service. Some plans meter or prohibit hotspot sharing — that is a **contract matter between you and your provider** (throttling/suspension), not something this project can decide for you.
- **No warranty.** Provided "as is"; the authors are not a party to your service agreement and accept no liability for misuse. Nothing here is legal advice.
