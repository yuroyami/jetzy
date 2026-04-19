# 📡 Jetzy

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.0+](https://img.shields.io/badge/iOS-16.0%2B-000000?logo=apple&logoColor=white)

**Jetzy is a peer-to-peer file-transfer app for Android & iOS, built end-to-end in Kotlin Multiplatform.** Send & receive files, photos, videos, folders, and text snippets across platforms without internet, without a cloud, and without an account.

One Kotlin codebase. One Compose UI tree. Two native apps. The *"z"* is for *zero servers*.

* * *

## Built with Kotlin Multiplatform

Jetzy treats KMP as a primary design constraint, not a partial rollout:

- 🎨 **One Compose UI tree** rendered natively on Android and iOS — no `#if` ladders, no parallel SwiftUI layer
- 🧠 **One viewmodel**, one state graph, one navigation stack — shared
- 📡 **One transfer protocol** (`JetzyProtocol.kt`) used by both sender and receiver regardless of OS
- 🧩 **~66% of the codebase is shared Kotlin.** The rest is platform transport plumbing (WiFi Direct, MultipeerConnectivity, hotspot provisioning, etc.)
- 🍎 **The iOS app is ~25 lines of Swift.** A single `iOSApp.swift` hands off to the Kotlin-driven Compose scene — everything else is Kotlin.

```
shared/src/commonMain   ≈ 5,550 lines Kotlin   — UI, viewmodel, protocol, models
shared/src/androidMain  ≈ 1,200 lines Kotlin   — WiFi Direct, Hotspot, platform utils
shared/src/iosMain      ≈ 1,680 lines Kotlin   — MultipeerConnectivity, LAN WiFi, QR scanner
iosApp/                 ≈   25 lines Swift     — entry point
```

* * *

## Features

- 📦 Files of any size, any format
- 📁 Entire folders, with automatic flattening
- 🖼️ Dedicated pickers for photos, videos, and text
- 🔍 Pair by QR code or local peer discovery
- 🔄 Secondary-QR reconnect to resume a dropped session
- 📱 Cross-platform: Android ↔ iOS at native speed
- 🌓 Light & dark themes
- 🌐 Localized UI
- 🎯 Responsive layout — scales to any screen size
- 🚫 No telemetry, no data collection, no account required

* * *

## Transport Methods

Jetzy picks the best available link for the sender/receiver pair.

| Transport | Android | iOS | Notes |
| :-- | :-: | :-: | :-- |
| WiFi Direct | ✔ | — | Android-hosted P2P group |
| Local Hotspot | ✔ | — | Android provisions a WPA2 hotspot; peer joins |
| MultipeerConnectivity | — | ✔ | iOS ↔ iOS over Apple's native stack |
| LAN WiFi | — | ✔ | iOS joins an existing network / the peer's hotspot |

When Android talks to iOS (or vice versa), the Android side typically provisions a hotspot and the iOS device joins — payloads then stream over a Ktor TCP socket on the shared network.

* * *

## How It Works

1. **Pick an operation** — *Send* or *Receive*
2. **Pick your peer's platform** — Android or iOS
3. **Pair** — scan a QR code, or let the app discover nearby peers
4. **Select content** — files, folders, photos, videos, or text
5. **Transfer** — the sender ships a manifest, the receiver stores payloads as they stream in

All data stays on the local link. No server sees your files.

* * *

## Architecture

| Layer | Stack | Scope |
| :-- | :-- | :-- |
| UI | Compose Multiplatform + Material 3 Expressive | 100% shared |
| Navigation | Navigation 3 | 100% shared |
| Viewmodel / state | Kotlin Coroutines + `StateFlow` | 100% shared |
| Transfer protocol | Kotlin + Ktor sockets | 100% shared |
| File access | FileKit | 100% shared |
| DI | Koin | 100% shared |
| QR encoding | QRose | 100% shared |
| QR scanning | CameraX (Android) / AVFoundation (iOS) | per-platform |
| P2P transport | WiFi Direct, Hotspot (Android); MultipeerConnectivity, LAN WiFi (iOS) | per-platform |

**Key sources:**
- `shared/src/commonMain/kotlin/jetzy/managers/JetzyProtocol.kt` — the transfer protocol, shared
- `shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt` — the single viewmodel
- `shared/src/commonMain/kotlin/jetzy/ui/AdamScreen.kt` — root composable, both platforms
- `shared/src/androidMain/kotlin/jetzy/managers/WiFiDirectP2PM.kt` — Android WiFi Direct
- `shared/src/androidMain/kotlin/jetzy/managers/HotspotP2PM.kt` — Android local hotspot
- `shared/src/iosMain/kotlin/jetzy/managers/MpcP2PM.kt` — iOS MultipeerConnectivity
- `shared/src/iosMain/kotlin/jetzy/managers/LanWifiP2PM.kt` — iOS LAN WiFi

* * *

## Building

**Requirements**

- Android Studio 2025.3.1 or newer
- JDK 21
- Xcode 16.3+ (for iOS)
- Min Android SDK: 26 (Android 8.0 Oreo)
- iOS deployment target: 16.0

**Android**

```bash
./gradlew :androidApp:assembleDebug
```

**iOS**

```bash
cd iosApp
pod install
open iosApp.xcworkspace
```

CocoaPods pulls the `shared` framework produced by `./gradlew :shared:podInstall` automatically.

* * *

## Privacy

Jetzy collects **no data**, contains **no analytics**, and talks to **no remote servers**. Every transfer is peer-to-peer. See [PRIVACY.md](PRIVACY.md) for the full statement.

* * *

## Feedback

Open an issue for bugs or feature requests, or start a discussion for general questions.

* * *

## Acknowledgments

- The [Kotlin](https://kotlinlang.org/) and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) teams at JetBrains — for making this project feasible
- [Ktor](https://ktor.io/) — socket networking, cross-platform
- [FileKit](https://github.com/vinceglb/FileKit) — cross-platform file handling
- [QRose](https://github.com/alexzhirkevich/qrose) — QR generation
- [Koin](https://github.com/InsertKoinIO/koin) — DI
- [Coil](https://github.com/coil-kt/coil) — image loading
