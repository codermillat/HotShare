#!/bin/bash
# hotshare-mac.sh — Mac Client role for HotShare (Host = Android on ISP, Client = this Mac)
#
# Topology:
#   ISP hotspot ──WiFi──> Android Host (HotShare :8080/:1080 + system Hotspot ON)
#                              ──Hotspot WiFi──> this Mac (all traffic via Host)
#
# What it does (dual-layer, so ISP sees ONLY the host):
#   1. PROXY LAYER (originates on host): system Web/Secure/SOCKS proxy -> <host-ip>:8080/:1080
#      Every proxied TCP connection is re-originated by the Android Host app, so the
#      ISP sees source = Host's WiFi IP/MAC, TTL=64. This is the primary bypass.
#   2. TTL LAYER (catch-all for non-proxy traffic): net.inet.ip.ttl=65, IPv6 OFF, DNS 8.8.8.8
#      Any packet that still goes via NAT loses 1 hop on the phone (65-1=64), so the
#      ISP can't tell it was tethered. IPv6 Hop Limit is a classic leak -> disabled.
#
# Usage:
#   ./hotshare-mac.sh <host-ip>                 # e.g. ./hotshare-mac.sh 192.168.43.1
#   ./hotshare-mac.sh "hotshare://join?ip=10.128.181.87&http=8080&socks=1080&..."
#   ./hotshare-mac.sh status                    # show current proxy/TTL/DNS/egress
#   ./hotshare-mac.sh undo | reset | off        # restore TTL 64, proxies OFF, IPv6 auto, DNS DHCP
#
# Prereqs: join the Android Hotspot WiFi FIRST. Private WiFi address OFF
# (System Settings -> Wi-Fi -> Details -> Private WiFi address OFF -> use Device MAC).
set -u

HTTP_PORT=8080
SOCKS_PORT=1080
DNS1="8.8.8.8"
DNS2="8.8.4.4"

usage() {
  cat <<'EOF'
HotShare Mac Client role — route ALL traffic via Android Host (ISP sees one device).

  Join Host hotspot first, then:
    ./hotshare-mac.sh <host-ip>          connect (proxy + TTL 65 + IPv6 off + DNS)
    ./hotshare-mac.sh "hotshare://..."   connect (parses QR URI from Android app)
    ./hotshare-mac.sh status             show proxy/TTL/DNS + egress IP test
    ./hotshare-mac.sh undo               disconnect (TTL 64, proxies off, IPv6 auto)

  Examples:
    ./hotshare-mac.sh 192.168.43.1
    ./hotshare-mac.sh 10.128.181.87        # TECNO-style hotspot subnet
EOF
}

# Detect the Wi-Fi networkservice name ("Wi-Fi" on most Macs, may be disabled-marked "*").
wifi_service() {
  local svc
  svc=$(networksetup -listallnetworkservices 2>/dev/null | grep -i "wi-fi" | sed 's/^\*//' | head -n1)
  if [ -z "${svc:-}" ]; then svc="Wi-Fi"; fi
  echo "$svc"
}

# Parse hotshare://join?ip=X&http=Y&socks=Z if given.
parse_host() {
  local arg="$1"
  if [[ "$arg" == hotshare://* ]]; then
    local ip http socks
    ip=$(echo "$arg" | sed -n 's/.*[?&]ip=\([^&]*\).*/\1/p')
    http=$(echo "$arg" | sed -n 's/.*[?&]http=\([^&]*\).*/\1/p')
    socks=$(echo "$arg" | sed -n 's/.*[?&]socks=\([^&]*\).*/\1/p')
    HOST_IP="$ip"
    [ -n "${http:-}" ] && HTTP_PORT="$http"
    [ -n "${socks:-}" ] && SOCKS_PORT="$socks"
  else
    HOST_IP="$arg"
  fi
  if [ -z "${HOST_IP:-}" ]; then echo "ERROR: could not parse host IP from '$arg'"; usage; exit 1; fi
}

check_host() {
  echo "==> Probing host proxy $HOST_IP:$HTTP_PORT ..."
  if curl -x "http://$HOST_IP:$HTTP_PORT" -s -o /dev/null --max-time 10 -w "HTTP proxy: %{http_code} in %{time_total}s\n" https://example.com; then
    :
  else
    echo "WARN: HTTP proxy probe failed. Is HotShare Host running + hotspot joined?"
  fi
}

do_connect() {
  local WIFI
  WIFI=$(wifi_service)
  echo "==> Mac Client role: routing ALL traffic via Host $HOST_IP (service: $WIFI)"
  echo "    (1) System proxies -> $HOST_IP:$HTTP_PORT/:$SOCKS_PORT  (2) TTL 65, IPv6 OFF, DNS $DNS1"

  check_host

  echo "--> Setting system proxies (sudo required)..."
  sudo networksetup -setwebproxy "$WIFI" "$HOST_IP" "$HTTP_PORT"
  sudo networksetup -setwebproxystate "$WIFI" on
  sudo networksetup -setsecurewebproxy "$WIFI" "$HOST_IP" "$HTTP_PORT"
  sudo networksetup -setsecurewebproxystate "$WIFI" on
  sudo networksetup -setsocksfirewallproxy "$WIFI" "$HOST_IP" "$SOCKS_PORT"
  sudo networksetup -setsocksfirewallproxystate "$WIFI" on
  # Force everything via proxy except loopback (empty bypass would also proxy localhost; keep minimal).
  sudo networksetup -setproxybypassdomains "$WIFI" localhost 127.0.0.1

  echo "--> TTL 65 (so ISP sees 64 after phone NAT -1)..."
  sudo sysctl -w net.inet.ip.ttl=65
  if ! grep -q "net.inet.ip.ttl=65" /etc/sysctl.conf 2>/dev/null; then
    echo "net.inet.ip.ttl=65" | sudo tee -a /etc/sysctl.conf >/dev/null
    echo "    persisted to /etc/sysctl.conf"
  fi

  echo "--> IPv6 OFF (Hop-Limit leak) + DNS $DNS1 $DNS2 + flush..."
  sudo networksetup -setv6off "$WIFI"
  sudo networksetup -setdnsservers "$WIFI" "$DNS1" "$DNS2"
  sudo dscacheutil -flushcache 2>/dev/null; sudo killall -HUP mDNSResponder 2>/dev/null || true

  echo ""
  echo "==> Shell env for this terminal (copy-paste for curl/npm/pip):"
  echo "    export http_proxy=http://$HOST_IP:$HTTP_PORT https_proxy=http://$HOST_IP:$HTTP_PORT"
  echo "    export HTTP_PROXY=\$http_proxy HTTPS_PROXY=\$https_proxy all_proxy=socks5://$HOST_IP:$SOCKS_PORT ALL_PROXY=\$all_proxy"
  export http_proxy="http://$HOST_IP:$HTTP_PORT" https_proxy="http://$HOST_IP:$HTTP_PORT"
  export HTTP_PROXY="$http_proxy" HTTPS_PROXY="$https_proxy" all_proxy="socks5://$HOST_IP:$SOCKS_PORT" ALL_PROXY="$all_proxy"

  echo ""
  echo "==> Verify (expect egress = Host's ISP IP, same pool as phone):"
  echo -n "    via-proxy egress IP: "; curl -x "http://$HOST_IP:$HTTP_PORT" -s --max-time 15 https://api.ipify.org || echo "(proxy curl failed)"
  echo ""
  echo -n "    direct egress IP (should match Host pool, TTL-masked): "; curl -s --max-time 15 https://api.ipify.org || echo "(direct blocked — fine, proxy is primary)"
  echo ""
  echo "Done. Keep HotShare Host foreground + hotspot ON. Run './hotshare-mac.sh status' anytime."
  echo "Undo: ./hotshare-mac.sh undo"
}

do_status() {
  local WIFI
  WIFI=$(wifi_service)
  echo "== HotShare Mac status (service: $WIFI) =="
  echo "-- proxies:"; networksetup -getwebproxy "$WIFI"; networksetup -getsecurewebproxy "$WIFI"; networksetup -getsocksfirewallproxy "$WIFI"
  echo "-- ttl:"; sysctl net.inet.ip.ttl
  echo "-- ipv6:"; networksetup -getinfo "$WIFI" | grep -i "IPv6" || true
  echo "-- dns:"; networksetup -getdnsservers "$WIFI"
  echo "-- gateway (should be Host hotspot IP):"; route -n get default 2>/dev/null | grep -E "gateway|interface" || true
  # If proxies are on, extract host for egress test
  local ph
  ph=$(networksetup -getwebproxy "$WIFI" 2>/dev/null | awk '/Server:/{print $2}' | head -n1)
  if [ -n "${ph:-}" ] && [ "$ph" != "(null)" ] && [ "$ph" != "0" ]; then
    echo -n "-- via-proxy egress: "; curl -x "http://$ph:$HTTP_PORT" -s --max-time 10 https://api.ipify.org || echo fail; echo ""
  fi
  echo -n "-- direct egress: "; curl -s --max-time 10 https://api.ipify.org || echo fail; echo ""
}

do_undo() {
  local WIFI
  WIFI=$(wifi_service)
  echo "==> Restoring Mac defaults (service: $WIFI)..."
  sudo networksetup -setwebproxystate "$WIFI" off || true
  sudo networksetup -setsecurewebproxystate "$WIFI" off || true
  sudo networksetup -setsocksfirewallproxystate "$WIFI" off || true
  sudo sysctl -w net.inet.ip.ttl=64
  sudo sed -i '' '/net.inet.ip.ttl=65/d' /etc/sysctl.conf 2>/dev/null || true
  sudo networksetup -setv6automatic "$WIFI" || true
  sudo networksetup -setdnsservers "$WIFI" Empty || true
  sudo dscacheutil -flushcache 2>/dev/null; sudo killall -HUP mDNSResponder 2>/dev/null || true
  unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY || true
  echo "Done. Proxies OFF, TTL 64, IPv6 auto, DNS DHCP."
}

case "${1:-}" in
  ""|-h|--help|help) usage; exit 0 ;;
  status) do_status; exit 0 ;;
  undo|reset|off|disconnect) do_undo; exit 0 ;;
  *) parse_host "$1"; [ -n "${2:-}" ] && WIFI_OVERRIDE="$2"; do_connect ;;
esac
