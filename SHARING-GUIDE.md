# HotShare Sharing Guide

> Android (host) → Android / macOS / any OS (client). No root required.

> **Authorized use only.** Share a connection **you are authorized to share**, with **devices you
> own**, in line with your provider's terms — see [`ACCEPTABLE-USE.md`](ACCEPTABLE-USE.md).
> Many plans allow multi-device sharing, some meter it, some prohibit it; that is a matter between
> you and your provider. Where an official multi-device add-on exists, prefer it.

**Scope:** share one working connection from an Android host with a second Android phone or a macOS
laptop. If a provider restricts simultaneous devices, the client typically shows
`Connected, no internet` — in that case check your plan and use the official add-on if there is one.

**HotShare v1.6** implements the host/client flow described here:
`HotShare/` is our own no-root app (sideload APK) that serves any client, including macOS.
Works on both 2.4 GHz and 5 GHz hotspots (in-app band selector; API 34+ forces the band, older
Android picks it automatically).

---

## Quick flow

### Host (the phone with internet)
1. Connect the phone to your Wi-Fi / upstream network and confirm it has internet.
2. Turn the hotspot on: Settings → Network → Hotspot → Wi-Fi hotspot ON. (Or let HotShare create a
   temporary LocalOnlyHotspot.)
3. Open HotShare → pick a **Hotspot band** (Auto, 2.4, or 5) → `Share internet (host)` → show the QR.

The proxy listens on `:8080` (HTTP) and `:1080` (SOCKS5) on the hotspot IP — check the address the
app shows (stock phones commonly use `192.168.43.1`; some OEMs differ). The UI reports both the AP
band and the upstream (STA) band so you can spot a conflict.

### Client — Android, VPN mode (all apps)
Scan the host QR → Connect. The QR carries a 12-char session token, and the tunnel authenticates
with it automatically (SOCKS5 user/pass), so no per-network proxy is needed.

### Client — Android, system proxy (simplest)
Join the hotspot, then set the Wi-Fi proxy to `<host-ip>:8080` — or from a shell:
`adb shell settings put global http_proxy <host-ip>:8080`. Some apps ignore the Wi-Fi proxy; use VPN
mode for those. Reset with `settings put global http_proxy :0`.

### Client — macOS
Join the hotspot → Wi-Fi → Details → Proxies → enable **Web Proxy (HTTP)** and
**Secure Web Proxy (HTTPS)** with `<host-ip>:8080`. Either band works.

---

## Band selection on single-radio phones

A phone with one Wi-Fi radio cannot reliably do upstream + hotspot on two different bands:

- **Upstream on 5 GHz** → keep the hotspot on 5 GHz (`Auto` usually does this).
- **Upstream on 2.4 GHz** → a 2.4 GHz hotspot is fine.

Forcing a 2.4 GHz hotspot while the upstream is 5 GHz drops the upstream link on single-radio
devices, and the hotspot ends up with no internet. The app shows the upstream band to make this easy
to avoid.

---

## Troubleshooting

| Symptom | Likely cause | What to try |
|---|---|---|
| `Connected, no internet` on the client | Upstream has no internet, or the plan restricts simultaneous devices | Confirm the host has internet; check your plan / official multi-device add-on |
| Client gets a login page | The provider's portal applies per device | Follow your provider's own instructions |
| Browser works, some apps don't | Those apps ignore the Wi-Fi proxy | Use the built-in VPN mode (QR) so all traffic is tunnelled |
| Slow or DNS failures | DNS or MTU | Set `8.8.8.8` manually, try the other band |
| `ping` fails but browsing works | A proxy carries TCP/UDP, not ICMP | Expected behaviour, not a fault |
| Hotspot has no internet after changing bands | Single-radio phone on mismatched bands | Match the hotspot band to the upstream band |

---

## Undo / reset

- **macOS client:** Wi-Fi → Details → Proxies → set back to Automatic/None.
- **Android client:** Wi-Fi → long-press the network → Modify → Proxy → None.
- **Host:** `Stop sharing` in HotShare, then turn the hotspot off.

---

## How it works

The host phone runs the proxy; clients route their traffic through it and the tunnel authenticates
with a per-session token. Clients never need direct outbound access. Everything stays between your
own devices — there is no account, no telemetry, and no developer-operated server.
