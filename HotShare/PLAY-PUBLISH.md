# Google Play publishing — HotShare

Status of the Play-readiness work and what is left to do in Play Console.
The technical blockers are done; the remaining items are console-side.

## 1. What was fixed in the build (done)

| Item | Before | Now |
|---|---|---|
| `targetSdk` | 34 (below the mandate) | **36** (Android 16) — required for submissions since 2026-08-31 |
| `compileSdk` / build tools | 34 | 36 / `buildToolsVersion '36.0.0'` |
| AGP / Gradle | 8.5.2 / 8.7 | **8.13.1** / **8.13** (max API 36.1, min Gradle 8.13) |
| Signing | `-Pandroid.injected.signing.*` + hardcoded `hotshare123` fallback | `keystore.properties` or `HOTSHARE_*` env vars; **no default password** |
| R8 | `minifyEnabled false` (keep rules unused) | `minifyEnabled true` + `shrinkResources true`, JNI keep rules added |
| Output | APK only | APK (sideload) **and signed AAB** (`scripts/build-aab.sh`) |
| Backup | `allowBackup=true`, secrets backable | hotspot SSID/password excluded from cloud backup + device transfer |
| Dead file | `app/libs/hev-socks5-tunnel.aar` (9 bytes, "Not Found") | deleted |

Versions: `versionName 1.6.0` / `versionCode 10`.

### Upload key
Play requires a **dedicated upload key** — never the sideload key:
```bash
keytool -genkeypair -keystore keystore/hotshare-upload.jks -alias hotshare \
  -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # fill in, gitignored
bash scripts/build-aab.sh
```
`keystore.properties` and `keystore/hotshare-upload.jks` are gitignored. **Back both up offline** — losing the upload key means a support request to Google to reset it.

## 2. Still blocking publication (Play Console + policy)

1. **Listing copy must stay purpose-neutral (highest risk).** Play's *Device and Network Abuse*
   policy forbids apps that "circumvent security protections" or use a service "in a manner that
   violates its terms of service", and protects "an authorized carrier's network".
   Store listing, screenshots, promo video and in-app copy must describe the app in terms of its
   legitimate use — *share your own connection with your own devices* — and must not reference
   detection avoidance, metering, plan restrictions or provider-specific techniques, and must not
   promise "free" or "unlimited" data. See `ACCEPTABLE-USE.md`. (Done: the evasion framing that used
   to be in the repo's docs, in-app string and macOS helper script has been removed — see §6.)
2. **Privacy policy URL** — mandatory (app requests location, camera, nearby-wifi). Draft in §3.
3. **Data safety form** — "No data collected / No data shared" is accurate (no analytics, no ads,
   no servers; all processing is on-device). Answer carefully: traffic is relayed between your own
   two devices, nothing is sent to the developer.
4. **VPN / VpnService declaration** — the app implements `VpnService`; declare it and confirm the
   compliance points (no data collection, no ad manipulation, no traffic redirection for profit).
5. **Foreground service type declaration** — `connectedDevice` (HostService) and `specialUse`
   (ShareVpnService, `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn"/>`).
   Play wants a description **plus a short video** demonstrating each type.
6. **Sensitive-permission disclosures** — location is used only to start the local-only hotspot
   (required by the platform on Android 8–12); camera only for on-device QR scanning. Show both
   in-app before requesting them.
7. **Store listing assets** — 512×512 icon, 1024×500 feature graphic, ≥2 phone screenshots,
   short + full description, category **Tools**, contact email, content rating (IARC), target audience.
8. **Account gates** — personal developer accounts created after 2023-11-13 must run a
   **closed test with ≥12 testers opted in continuously for ≥14 days** before production access
   can be requested; new accounts also need device verification. Budget ~5–8 weeks end to end.
9. **Reviewer notes** — the app needs two devices to demonstrate. State that explicitly in the
   review notes ("two Android devices required; host on device A, client on device B; steps …"),
   because a single-device reviewer otherwise sees a non-functional app.

Interaction with the carrier/ISP on the device's own connection is the app's core feature; the
policy risk is about *how it is presented*, not about the proxy/VPN APIs themselves.
## 3. Privacy policy template

Host at a public HTTPS URL (GitHub Pages works) and paste the URL into Play Console. Replace the
bracketed placeholders and have it reviewed before publishing.

```
HotShare — Privacy Policy
Last updated: [DATE] · Contact: [EMAIL]

HotShare shares an internet connection between devices you own, using a hotspot, a local
HTTP/SOCKS5 proxy, and an on-device VPN (VpnService) client.

We do not collect, store, transmit or sell any personal data. The app has no user accounts,
no analytics, no advertising SDKs, and no developer-operated servers.

Data processed on your device only:
 • Location — requested solely because the Android platform requires it to start a local-only
   hotspot (Android 8-12). It is never stored or sent anywhere.
 • Camera — used only to scan HotShare QR codes. Frames are processed on-device and are never
   saved or transmitted.
 • Wi-Fi details — the hotspot name and password you configure are stored locally so the app can
   show a QR code, and are excluded from Android cloud backup and device transfer.
 • Network traffic — when you run the client, traffic is routed through the tunnel to the host
   device you chose. It passes only between your own devices and is not inspected, logged, or
   sent to us.

Permissions are used only for the features above. You can revoke any of them in Android Settings;
doing so disables the related feature.

Third-party components: AndroidX / Jetpack Compose, CameraX, ZXing and hev-socks5-tunnel
(MIT / Apache-2.0) are bundled as libraries. See THIRD-PARTY-NOTICES.

Children: the app is not directed at children under 13.

Changes to this policy will be posted at this URL.
```

## 4. Store listing copy (purpose-neutral draft)

**Title (≤30 chars):** `HotShare — Share Internet`

**Short description (≤80 chars):** `Share this phone's internet with your own devices. No root, no account.`

**Full description:**

```
HotShare shares one phone's internet connection with your own devices — another phone, a laptop
or a tablet — over the phone's own hotspot. No root, no account, no ads, no telemetry.

• Host: turn the hotspot on, tap "Share internet (host)", show the QR code.
• Client (Android): scan the QR — app traffic is tunnelled to the host.
• Client (macOS / other): point the system HTTP/SOCKS proxy at the host address.
• Live speed, client count and uptime in the notification.
• Works on 2.4 GHz and 5 GHz hotspots.

The app shares a connection you are authorized to share with devices you own; see the acceptable
use page for details. No data is collected — there are no accounts, no analytics and no
developer-operated servers.
```

**Category:** Tools · **Ads:** No · **Data collected:** None

## 5. Pre-submission checklist

- [ ] `bash scripts/build-aab.sh` produces a signed `.aab` (verified with `jarsigner`)
- [ ] `aapt2 dump badging` reports `targetSdkVersion:'36'`
- [ ] `unzip -l dist/*.aab | grep lib/` lists both `arm64-v8a` and `armeabi-v7a`
- [ ] Listing, screenshots and promo video describe own-device sharing only — no provider or
      carrier references, no metering or "free/unlimited" claims
- [ ] Privacy policy hosted at a public URL and pasted into Play Console
- [ ] Data safety, VPN (VpnService) and foreground-service-type declarations completed
- [ ] Closed test with ≥12 testers for ≥14 days completed (new personal developer accounts)
- [ ] Reviewer notes describe the required two-device flow

## 6. Repo cleanup log (v1.6)

Removed from this repository, to keep the published project limited to its documented, authorized
use:

- The provider-control circumvention document that shipped as `ISP-Hotspot-Sharing-Manual.md` is
  gone; the useful setup/troubleshooting content now lives in `SHARING-GUIDE.md`.
- The macOS client helper (`scripts/hotshare-mac.sh`) no longer applies machine-level network
  tweaks or device-identity advice. It configures the system proxy and DNS only. Its `undo` action
  still restores macOS defaults, which cleans up any machine that ran an earlier release.
- In-app and README wording no longer describes the app in terms of what a provider can see.
- Added `ACCEPTABLE-USE.md` (intended use, provider terms, no commercial resale, contribution policy).