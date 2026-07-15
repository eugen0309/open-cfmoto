# Wi-Fi Direct (P2P) support — CL-C450

## Why this exists

The 675 SR connects over an **infrastructure Wi-Fi AP** (`WifiNetworkSpecifier`, gateway
`192.168.0.1`, phone `192.168.0.50`). The **CL-C450** instead brings up the EasyConn head unit as a
**Wi-Fi Direct (Wi-Fi P2P) Group Owner**, which `WifiNetworkSpecifier` cannot join. Before this
change the app took the AP path unconditionally, so the CL-C450 never associated.

This change adds a **second, selectable connection path** without touching the verified AP path or
any of the PXC/media pipeline.

## What changed (files)

| File | Change |
|------|--------|
| `BikeWifiP2p.kt` (new) | Wi-Fi Direct join via `WifiP2pManager`: discover peers (for logging), `connect()` to the bike's group using the QR ssid/passphrase as a legacy client, then resolve the phone's `192.168.49.x` address and the GO (bike) `192.168.49.1`. |
| `EasyConnProber.kt` | `start()` gains optional `bindIpOverride` / `gatewayOverride`. When set (P2P), sockets bind to the P2P-interface IP directly and no `Network`/`bindProcessToNetwork` is used. AP path is unchanged. |
| `MainActivity.kt` | `joinAndStart()` now selects AP vs P2P; new `joinP2pAndStart()`; P2P runtime permission; Settings → **Connection mode**; P2P teardown on Stop/onDestroy. |
| `BikeConfig.kt` | New `Transport` enum (AUTO/AP/P2P) persisted; new `CL_C450` model entry (placeholder geometry — see below). |
| `AndroidManifest.xml` | Added `NEARBY_WIFI_DEVICES` (Android 13+ P2P) and the non-required `wifi.direct` feature. |

## How the topology differs

```
AP mode (675 SR):                    P2P mode (CL-C450):
  phone 192.168.0.50  ── AP ──          phone 192.168.49.x ── Wi-Fi Direct ──
  bike  192.168.0.1  (gateway)          bike  192.168.49.1  (Group Owner)
  join: WifiNetworkSpecifier            join: WifiP2pManager.connect()
  process bound to the bike Network     sockets bound to the P2P interface IP
```

Everything after association is **identical**: the phone opens TCP servers on 10920/10921/10922,
sends the `0x70000010` probe to `bike:10930`, and the bike connects back and drives the PXC
handshake. The P2P subnet is on-link, so binding the socket source IP is enough to route over the
P2P interface, and Android Auto/GMS keep using cellular for map tiles (no VPN, no route capture).

## How to test on the CL-C450

1. Install the APK (see `BUILD.md`).
2. In the app: **Settings → Connection mode**. Leave on **Auto** for the first try. If the log shows
   it chose AP (because the CL-C450 QR advertises AP too), come back and pick **Force Wi-Fi Direct
   (P2P)**, then Scan again. (This toggle exists precisely so you can force P2P without a rebuild.)
3. Tap **Start Android Auto** (or just Scan for a bike-only test), scan the CL-C450 QR, and accept
   any system permission / Wi-Fi Direct invitation prompt on the phone.
4. Let it run ~30 s whether it works or not, then hit **Share** and send the log.

## What the log should show (and what to look for)

Search the log for `[P2P]`. A healthy run looks like:

```
[P2P] starting Wi-Fi Direct connect
[P2P]   qr ssid='...' mac=... action=... (p2p=true ap=...)
[P2P] discoverPeers: started
[P2P] peer: name='...' addr=xx:xx:.. status=AVAILABLE isGO=true      <- the bike
[P2P] connect(): joining group name='...' as legacy client …
[P2P] connect(): request accepted — waiting for group to form
[P2P] conn: groupFormed=true isGO=false goAddr=192.168.49.1
[P2P] group: name='DIRECT-..' owner='..' iface=p2p-wlan0-0 clients=1
[P2P] *** connected: phone=192.168.49.x bike(GO)=192.168.49.1 — starting PXC ***
... then the usual [:10922]/[:10921]/[:10920] handshake + framesSent ...
```

The parts that are **unverified against hardware** and most likely to need a tweak — capture these:

- **The raw QR line** (`QR raw: …` / `QR parsed: …`). This reveals the CL-C450's real `action`
  bitmask and whether its `ssid` is already a `DIRECT-…` name. That single line resolves most of the
  remaining unknowns.
- **`[P2P] credential-join not usable: …`** — means the QR ssid isn't a valid Wi-Fi Direct group
  name (must start with `DIRECT-`). If you see this, the QR carries the group name differently and
  we'll map the real name from the `[P2P] peer:` / `[P2P] group:` lines instead (likely switching to
  a WPS/push-button connect keyed on the bike's `mac`).
- **`[P2P] !! WE became the Group Owner`** — the join created a new group instead of joining the
  bike's. Needs a connect-config tweak.
- **`[BIKE-REPORT] *** panel=WxH …`** — the CL-C450's real panel resolution. The `CL_C450` entry in
  `BikeConfig.kt` currently uses **placeholder** 800×384 geometry copied from the 675; replace it
  with the reported `panel=WxH` (and pick the smallest AA resolution that contains it) so the video
  isn't stretched. The prober logs a `!! bike reports … but selected model is …` warning if they
  differ.

Paste the exported log back and the P2P connect can be corrected from it in one iteration — this is
the same implement → one logged bike session → adjust loop the rest of the project uses.
