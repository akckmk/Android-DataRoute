# DataRoute

An Android app that keeps your phone connected to a **local Wi-Fi with no internet** (for example a UniFi
network whose ISP is down) **and** uses **cellular for everything else** at the same time:

* the UniFi Protect NVR / cameras on `192.168.x.x` stay reachable over Wi-Fi,
* streaming, Claude, Google Messages, etc. go out over mobile data,
* when Wi-Fi's internet is healthy again it can switch back (Automatic failover).

## Why a plain Android phone can't do this by itself

Android picks **one** "default network" for ordinary traffic. If you stay on a Wi-Fi without internet, the default
stays Wi-Fi and the internet is dead. If Android flips the default to cellular, traffic for `192.168.x.x` also
leaves via cellular and the NVR becomes unreachable. Apps can't opt out of that for other apps.

## How DataRoute solves it

DataRoute runs a local **VPN service** (nothing leaves your phone for a VPN server). It captures IPv4 traffic and
re-originates every connection on the *right physical network*:

```
 apps ──► TUN device ──► ForwardEngine ──┬─► socket bound to Wi-Fi     (192.168/16, 10/8, NVR IP, Wi-Fi subnet…)
                                          └─► socket bound to cellular  (everything else)
```

* `core/` – pure-Kotlin packet engine: IPv4/TCP/UDP parsing, a user-space TCP implementation (handshake, windowing,
  retransmission, FIN/RST), UDP relay, routing policy. **Unit-tested on the JVM against real loopback sockets**
  (`./gradlew -PcoreOnly :core:test`).
* `app/` – Android UI (Compose), `VpnService`, network monitoring (`ConnectivityManager`), diagnostics, an in-app
  NVR web viewer.

Each socket is `protect()`ed (so it can't loop back into the tunnel) and `bindSocket()`ed to the Wi-Fi or cellular
`Network`, which is what makes the split independent of Android's "default network" choice.

## Using it

1. Install the APK (from the **Actions → Android CI → DataRoute-debug-apk** artifact, or build it yourself).
2. Open **Settings** and enter your NVR/console IP (e.g. `192.168.1.10`). Defaults treat all private ranges as local.
3. Connect to your UniFi Wi-Fi. If Android asks *"This network has no internet, stay connected?"* choose **stay**.
   Make sure **mobile data is on**.
4. Tap **Start routing** and accept the VPN prompt.
5. On **Status**, tap **Run tests** – it checks the NVR over Wi-Fi, internet over cellular, and both *through the
   tunnel* exactly like any other app would. The **Log** tab shows every connection and which network it took.
6. Open UniFi Protect (or *Open NVR here*) – it now works while the rest of the phone uses cellular.

### Settings worth knowing

| Setting | Meaning |
|---|---|
| Internet traffic → Automatic failover | Wi-Fi if Android says it has working internet, else cellular. |
| Internet traffic → Always cellular | Force mobile data even when Wi-Fi works. |
| Local traffic → Relay (default) | Camera traffic is relayed by the app on the Wi-Fi network. Works regardless of Android's default network. |
| Local traffic → Leave to Android | Local ranges skip the tunnel (faster, no relay) but only work while Android keeps Wi-Fi as default. |
| Apps that bypass | Apps that refuse to run behind a VPN (some banking apps) keep using Android's default network. |

## Limitations (read these)

* **One VPN at a time** on Android. DataRoute can't run together with another VPN (Tailscale, WireGuard, …).
* **IPv4 only.** IPv6 isn't routed into the tunnel, so apps fall back to IPv4 (fine in practice).
* ICMP (`ping`) and fragmented packets aren't forwarded; mDNS/`.local` names aren't relayed – use IP addresses.
* Camera video in *Relay* mode passes through user-space code. It's tested to multi-MB/s on the JVM, but
  many simultaneous 4K streams on a weak phone may be better served by *Leave to Android* when Wi-Fi is the default.
* Holding the mobile-data connection up while on Wi-Fi costs some battery.
* The Android integration (VpnService, per-network sockets) can only be exercised on a real device. The packet
  engine is covered by automated tests; the rest is verified by compiling against the Android SDK and by the
  in-app **Run tests** button on your phone.

## Building

```
./gradlew :core:test            # packet engine tests
./gradlew :app:assembleDebug    # needs the Android SDK; APK in app/build/outputs/apk/debug/
```

Requires JDK 17 and the Android SDK (platform 35). CI does both on every push.
