# Privacy Policy

**Effective date:** April 19, 2026

Jetzy does not collect, store, transmit, or share any personal data. This document explains exactly what that means and why.

* * *

## What Jetzy does *not* do

- ❌ No analytics or telemetry
- ❌ No crash reporting sent to any third party
- ❌ No advertising identifiers
- ❌ No accounts, logins, or sign-ups
- ❌ No cloud or server-side storage of your files
- ❌ No contact list, address book, or social-graph access
- ❌ No sharing of anything with third parties

There is **no backend**. Jetzy's developer does not operate any server that your device communicates with.

* * *

## What Jetzy *does*

Jetzy moves files directly between two devices over a peer-to-peer link. Depending on the two
devices and what each supports, the link is one of:

- **WiFi Direct** (Android ↔ Android)
- **Local Hotspot** (Android provisions a WPA2 hotspot, peer joins)
- **MultipeerConnectivity** (iOS ↔ iOS, Apple's native stack)
- **Local WiFi / mDNS** (both peers on the same network)
- **Wi-Fi Aware** (on supported hardware)
- **Bluetooth** (short-range fallback)

Files never leave the local link between the two devices. There is no intermediate server, cloud storage, or logging service involved.

* * *

## Local storage

- Files you pick to **send** are read in place — Jetzy does not copy them anywhere.
- Files you **receive** are written to a temporary directory on your device while the transfer is in progress, then moved to the destination you choose. Temporary files are cleaned up at session boundaries.
- Preferences (e.g. chosen theme, your device name) are stored locally on your device only.
- During an active transfer Jetzy keeps a short in-memory diagnostics log (recent connection
  events, last ~50 lines) to help you understand a failed transfer. It lives only in the running
  app, is never written to disk in release builds, and is never transmitted anywhere.

* * *

## Security of the transfer

Jetzy transfers run over the **local link only** — your home/office Wi-Fi, an Android-provisioned
WPA2 hotspot, or Apple's encrypted MultipeerConnectivity. Treat a transfer like handing someone a
USB stick on the network you are both on:

- Use Jetzy on networks you trust. Another device already on the **same** Wi-Fi network or hotspot
  is, by design, able to reach your device during pairing.
- The pairing QR code contains the credentials to join the transfer's network. Show it only to the
  person you are sending to, and don't photograph or share it.

End-to-end payload encryption and explicit peer-identity verification are on the roadmap; until then,
rely on the trust of the local network you choose.

* * *

## Permissions Jetzy requests

| Permission | Why it's needed |
| :-- | :-- |
| Wi-Fi / Nearby devices / Local network | To discover and connect to the peer device. Used `neverForLocation` — Jetzy never derives your location from it. |
| Bluetooth (scan/connect) | Only for the short-range Bluetooth transfer fallback. `neverForLocation`. |
| Photos / Files | To let you pick files to send. Received files are saved via the system media store / a folder you pick — no broad storage permission is requested. |
| Camera | Only for scanning QR codes during pairing |

Jetzy uses these permissions **only** for the transfer itself. No data gathered via permissions is stored, transmitted, or retained beyond the active session.

* * *

## Third-party libraries

Jetzy uses open-source libraries — Ktor, Koin, FileKit, QRose, Coil, among others. In Jetzy's configuration, none of them transmit user data to remote services. They run entirely on-device.

* * *

## Children's privacy

Jetzy is safe for users of any age. Because it collects no data at all, there are no age-gating or parental-consent concerns.

* * *

## Changes

If this policy ever changes, a new effective date will appear at the top, and the change will be documented in the repository's commit history.

* * *

## Contact

For questions about this privacy policy, please open an issue in the project repository.
