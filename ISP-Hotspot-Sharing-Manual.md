# Hotspot & Internet-Sharing Manual
> Android (Host) → macOS & Android (Clients) | Manual + Root Clause

> **Authorized use only.** This manual documents standard networking techniques for sharing
> an internet connection **you are authorized to share**, with **devices you own** — the same
> techniques used by open-source tools like VPN Hotspot (Mygod) and PdaNet. Whether hotspot /
> multi-device sharing is permitted is a contractual question between you and your provider:
> many plans allow it, some meter it, some prohibit it. Violating your provider's terms is a
> **civil/contract matter** (throttling, suspension), not a crime — but check your plan first.
> Rooting risks (warranty, OTA, Play Integrity) are noted in §3.

**Date:** 2026-09-13
**Scope:** Sharing one working connection (Android host) with a Mac / 2nd Android. Symptom when the provider restricts simultaneous devices: client shows `Connected, no internet`.

**🔥 APP READY (v1.2, 2026-09-14):** `HotShare/` — our own plug-and-play app (sideload APK, no root, no Play Store)
automates this manual's Proxy Share for Android→Android (and serves any client incl. macOS).
**Works on both 2.4 GHz and 5 GHz hotspots** (in-app band selector; API 34+ forces the band,
older picks automatically). Home-network test PASSED end-to-end (see `HotShare/dist/TEST-REPORT.md`). ISP test pending.

### HotShare quick flow (replaces "Every Proxy" in Manual A/B)
- **Host phone** (on ISP): Settings → Hotspot ON → open HotShare → pick **Hotspot band**
  (Auto, or **2.4 GHz when ISP is 5 GHz** — many phones can't do 5+5 on one radio) → `Share Internet (Host)`
  → proxy `:8080/:1080` on the hotspot IP (TECNO: `10.128.181.87`, stock: `192.168.43.1` — check `ip addr show ap0`).
  UI shows the actual AP band + your ISP (STA) band so you can avoid a conflict.
- **Client Android (VPN, all apps):** scan the host QR → Connect. The QR carries a 12-char token;
  the tunnel authenticates automatically (SOCKS5 user/pass). No per-network proxy needed.
- **Client Android (system proxy, simplest):** join hotspot → `adb shell settings put global http_proxy <host-ip>:8080`
  (or WiFi UI proxy) → browse. Default mode accepts any hotspot-subnet client (the hotspot WPA2
  password is the gate); enable **Require QR token** on the host for strict auth, then add `user:token`
  to the proxy URL. Reset: `settings put global http_proxy :0`.
- **Client macOS:** join hotspot → proxies `host-ip:8080` exactly as §A1/A2 below (works on either band;
  no token needed unless the host enabled strict token mode).
- ISP sees only the Host's TTL-64 traffic (proxy traffic originates on host).

---

## 0. Why All Tethering Modes Fail The Same Way

1. **MAC Binding / Captive Portal:** ISP binds login to host phone MAC+IP. Client gets private IP `192.168.43.x / 192.168.137.x` behind NAT — no login, dropped or redirected. Do NOT login again on client — you kick host off.
2. **TTL Detection:** Phone packets TTL=64. Laptop packets: Laptop → Phone (TTL-1) → ISP sees 63, knows tethered.
3. **IPv6 / DPI Leak:** IPv6 Hop Limit or DPI exposes tether even if IPv4 fixed.

Fix = **Proxy Share** (traffic originates from phone) or **TTL Fix** (send 65 so arrives as 64).

> On BOTH devices: **Use Device MAC**, NOT Randomized.
> Android: WiFi → ISP → Privacy → `Use device MAC`
> macOS: WiFi → Details → `Private WiFi address OFF`

---

## 1. Manual A: Android (Host) → macOS (Client) — No Root

### A1. Proxy Share [Recommended, no root]

**On Host Android:**
1. Connect to ISP, verify internet.
2. Turn ON WiFi hotspot: Settings → Network → Hotspot → WiFi hotspot ON.
   - **Band rule (tested):** on a **single-radio** phone the AP must use the **same band as the ISP uplink**.
     Samsung M34 (tested) can do **5 GHz ISP + 5 GHz AP**, but forcing a **2.4 GHz AP while the ISP is 5 GHz
     drops the WiFi uplink** → the hotspot has no internet. So: ISP 5 GHz → keep AP 5 GHz (HotShare `Auto`);
     ISP 2.4 GHz → 2.4 GHz AP is fine. HotShare shows your ISP band so you can pick.
   - Gateway usually WiFi `192.168.43.1` / `192.168.44.1`, USB `192.168.137.1`.
3. Start HotShare `Share Internet (Host)` (proxy `:8080` HTTP + `:1080` SOCKS5 on the AP IP), or install `Every Proxy` (Play Store) → Start `HTTP` on `8080` + `SOCKS` on `1080`.
4. Note IP: `Running on 192.168.43.1:8080`. Keep app allowed in background.

**On Client macOS (NEW Mac role — script does A1+A2 together):**
1. Connect to Android Hotspot. Set `Private WiFi address OFF` (Device MAC).
2. Run `bash HotShare/scripts/hotshare-mac.sh <host-ip>` (e.g. `192.168.43.1` or TECNO `10.128.181.87`).
   It sets Web/Secure `:8080` + SOCKS `:1080` + TTL 65 + IPv6 OFF + DNS, so ALL traffic
   routes via Host and ISP sees only Host (proxy sockets originate on phone, TTL 64 on wire).
   Verify: `./hotshare-mac.sh status`. Undo: `./hotshare-mac.sh undo`.
3. Manual (same, without script): System Settings → Wi-Fi → (i)/Details → Proxies → Enable:
   ```
   [x] Web Proxy (HTTP)  Server: 192.168.43.1 Port: 8080
   [x] Secure Web Proxy  Server: 192.168.43.1 Port: 8080
   [x] SOCKS Proxy       Server: 192.168.43.1 Port: 1080
   ```
3. Uncheck `Bypass proxy for simple hostnames` → OK → Apply.
4. Test:
   ```bash
   curl -x http://192.168.43.1:8080 -I https://google.com
   export http_proxy=http://192.168.43.1:8080
   export https_proxy=http://192.168.43.1:8080
   export all_proxy=socks5://192.168.43.1:1080
   ```

> USB Note: macOS has no native RNDIS for Android USB. Use WiFi. If cable mandatory, buy `EasyTether` ($10): app on Android + driver on Mac, then same proxy.

### A2. TTL Fix [System-wide, all apps + games]

Send TTL=65 from Mac so after Android -1, ISP sees 64.

```bash
# temp fix (lost on reboot) — test first
sudo sysctl -w net.inet.ip.ttl=65

# make permanent
echo "net.inet.ip.ttl=65" | sudo tee -a /etc/sysctl.conf

# disable IPv6 on Wi-Fi (ISP often detects via IPv6)
networksetup -setv6off Wi-Fi

# flush DNS + set manual DNS
sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder
networksetup -setdnsservers Wi-Fi 8.8.8.8 8.8.4.4
```

Now connect to Android Hotspot normally. No proxy needed.

Undo:
```bash
sudo sysctl -w net.inet.ip.ttl=64
sudo sed -i '' '/net.inet.ip.ttl=65/d' /etc/sysctl.conf
networksetup -setv6automatic Wi-Fi
```

---

## 2. Manual B: Android (Host) → Android (Client) — No Root

Second phone can't change TTL without root, so use Proxy.

**On Host (with internet):**
1. Connect to ISP, turn ON WiFi Hotspot (2.4 GHz preferred).
2. Start `Every Proxy` on `8080`.

**On Client (no internet):**
1. Connect to Host Hotspot.
2. Settings → WiFi → Long-press Host → Modify → Advanced → Proxy → `Manual`:
   ```
   Proxy hostname: 192.168.43.1
   Proxy port: 8080
   Bypass: localhost
   ```
3. Save, toggle WiFi OFF/ON, test in Chrome.

**For apps that ignore WiFi proxy:**
On client install `Postern` / `ProxyDroid` / `Every Proxy` client mode:
- Type HTTP, Host `192.168.43.1`, Port `8080`
- Enable `Global Proxy / VPN Mode` → Accept VPN dialog.
- Or Private DNS → `dns.google`, or Static IP DNS `8.8.8.8`.

---

## 3. Root Clause — Rooted Devices Only

> Risks: voids warranty, breaks OTA, trips SafetyNet/Play Integrity (GPay/banking/Netflix may stop). Backup first. For fair-use of paid quota on owned devices; prefer ISP 2-device add-on where available.

### 3.1 If CLIENT Android is Rooted

No proxy needed — plain hotspot works.

1. Install `TTL Master` → Set `65`, Apply on boot ON.
2. Or via Termux + su:
   ```bash
   su
   cat /proc/sys/net/ipv4/ip_default_ttl
   echo 65 > /proc/sys/net/ipv4/ip_default_ttl
   sysctl -w net.ipv4.ip_default_ttl=65
   ip6tables -t mangle -A POSTROUTING -j HL --hl-set 65
   ```
3. Reconnect to host hotspot.

### 3.2 If HOST Android is Rooted [Best — no client config]

Repeat ISP WiFi without NAT.

Option 1 — `VPN Hotspot` by Mygod (root):
- Grant root → Enable `WLAN Repeater` (not normal Hotspot).
- Upstream = ISP hotspot, downstream AP `MyShare` 2.4 GHz WPA2.
- Enable `TTL fix 65` in settings. Clients need no proxy/TTL.

Option 2 — Manual iptables:
```bash
su
iptables -t mangle -A POSTROUTING -j TTL --ttl-set 64
ip6tables -t mangle -A POSTROUTING -j HL --hl-set 64
iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
settings put global tether_dun_required 0
```

Verify: `cat /proc/sys/net/ipv4/ip_default_ttl`, `iptables -t mangle -L -v -n`

---

## 4. Troubleshooting & Undo

| Symptom | Cause | Fix |
|---|---|---|
| `Connected, no internet`, host works | TTL block | Proxy A1/B or TTL A2 / Root 3.1 |
| Client gets login page | MAC binding | DON'T login again. Close page, set proxy. Use Device MAC. |
| Browser works, games/apps don't | App ignores proxy | TTL method or Postern Global mode or Root Repeater |
| Slow / DNS fails | DNS/MTU | Set `8.8.8.8`, clamp MSS, try 2.4 GHz |
| `ping` fails but browser works | Expected with proxy | Normal — proxy != ICMP. Use TTL if need ping |
| Host kicked off after client login | Single-device | Ask ISP whitelist / 2-device plan |

Undo:
- macOS: `sudo sysctl -w net.inet.ip.ttl=64`, delete line from `/etc/sysctl.conf`, `networksetup -setv6automatic Wi-Fi`
- Android client: WiFi Modify → Proxy `None`
- Root: TTL Master restore 64, `iptables -t mangle -F`, disable Repeater
- Host: Stop Every Proxy, Hotspot OFF

### Long-term: Travel Router + MAC Clone
GL.iNet Mango / TP-Link WR902AC in WISP mode → Clone phone MAC → Login ONCE on router → Connect all devices to router. ISP sees 1 device.


