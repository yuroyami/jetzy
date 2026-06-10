# Jetzy Frontier — uncharted directions + third-pass audit

_2026-06-10 · verified against HEAD `829d5b8` (v0.5.0) + working tree (iOS QR race-dialog WIP) · companion to JETZY_DEEP_REVIEW.md and PERFORMANCE_AUDIT.md. Part A is product invention; Part B is a fresh audit pass that deliberately avoids re-reporting anything in the two earlier documents._

---

## Part A — Things no file-transfer app has done

Jetzy's unique asset is not any single transport — it's the **negotiator brain over a 9-transport registry, plus a chunked, CRC-ledgered, resumable engine that runs over any duplex byte channel**. Every idea below is graded on how directly it rides that asset. Honesty notes on adjacent prior art are inline; the claim is never "no component exists anywhere," it's "nobody has productized this, and Jetzy is structurally closer to it than anyone."

These deliberately go *beyond* the deep review's HACKs 7–13 (word codes, capability beacons, mDNS TXT, deep links, NFC, WebRTC relay, ultrasonic handshake) — those are bootstrap improvements; these are new categories.

### Theme 1 · Transports nobody ships

**1. Gear 0 — the full-duplex optical link (screen ↔ camera).**
QR is already a discovery rung; make it a *data* transport. Animated, fountain-coded QR (LT/RaptorQ — loss-tolerant, needs no backchannel) turns any screen into a transmitter and any camera into a receiver at ~5–30 KB/s. Both phones showing + scanning simultaneously = full duplex with real ACKs. Research prototypes exist (txqr, cimbar); **no consumer transfer app ships it, and nobody has done the bidirectional version.** Use cases that are pure Jetzy: airplane mode / radio-off environments, transfer across glass (hospital isolation, prison visitation, drive-through), RF-denied rooms — and the killer one in Theme 6: **IT-locked corporate laptops** where USB, AirDrop, and the network are all blocked but a browser + webcam are not. The engine already runs over any `ByteReadChannel/ByteWriteChannel`, so this is "just" a new bridge like `WifiAwareBridge`.

**2. Data through a live phone call.**
Not ultrasonic discovery (HACK 13) — pushing small payloads through the *voice path of an active call* (audible-band FSK, ggwave-style; voice codecs kill ultrasonic but pass low-rate audible tones). Bitrate is tiny (~tens of bytes/s), but that's enough for a contact card, a URL, an OTP — or, the real play: **a Jetzy session token that bootstraps the WebRTC relay (HACK 12) once it exists.** "You're on a call with someone remote; the call itself becomes the pairing channel." Solves rendezvous-at-distance with zero accounts, zero typing. Nobody does in-call data transfer.

**3. Sneakernet routing — parcels that ride other people's phones (DTN).**
Queue an encrypted parcel for Carol; it hops onto mutual-friend Bob's phone when you meet him, and delivers when *he* meets Carol. Delay-tolerant networking (spray-and-wait) with chunk striping across multiple mules — the receiver's ledger already accepts chunks in resumable pieces from any session, and `ResumePlanner` is pure. Briar does DTN *messaging*; nobody does multi-hop *file* delivery with chunk-level striping. Niches where this is the only thing that works: festivals, cruise ships, campuses, disaster zones, censored networks.

### Theme 2 · The negotiator brain as the product

**4. Heterogeneous channel bonding ("gear-blend").**
The roadmap's gear-shift picks the best link. The step nobody has taken in consumer transfer: **use all of them at once** — stripe chunks across BLE + Wi-Fi Direct + LAN simultaneously, MPTCP-style but across radio *technologies*. The substrate is unusually close: chunked protocol, per-file ledger, `getManagerForTechnology` factory, and `TransportCoordinator.schedule` (Happy-Eyeballs) already exist; bonding is the schedule that never cancels the losers. Payoff beyond speed: a link dying mid-transfer doesn't even pause the stripe — resilience as a visible, demo-able wow.

**5. Per-peer learned ladders + predictive pre-connection.**
The negotiation cache: "with Maya's Pixel, mDNS always wins in <1s; with Dad's iPhone, hotspot-QR is the only thing that ever worked — skip the 6s fallback dance." Store outcome statistics per paired peer; pre-warm the winning probe the moment files are staged, so tapping the peer is instant. AirDrop pre-warms radios; **a brain that learns per-relationship transport history is new** — and it's the owner's #1 chicken-egg problem solved by memory instead of guessing.

### Theme 3 · Time-shifted delivery

**6. Deliver-on-meet — the parcel queue.**
Decouple "send" from "both phones out and unlocked right now": stage a parcel for a known peer, forget it; next time the devices sense each other (background BLE/Aware beacon), they auto-gear-up, transfer, and notify. No cloud, no both-online-now, no babysitting a progress bar. This is the single most life-changing everyday behavior no local-transfer app has — AirDrop is synchronous, cloud links are not local. Requires the pairing identity (B3's fix) + persisted ledger; the beacon can be the existing advertise path on a duty cycle.

**7. Charging-window scheduling ("tonight mode").**
For multi-GB parcels: defer until both devices are on power and idle (WorkManager constraints / BGProcessingTask), then run unattended. This is also the honest answer to iOS's background limits (see Part B — backgrounding is currently a transfer-killer): schedule into the windows the OS *does* grant.

### Theme 4 · Security from physics (proximity cryptography)

**8. Co-presence threshold unlock — k-of-n phones in one room.**
Encrypt a parcel so decryption needs key shares (Shamir) collected over BLE from k of n trusted devices *physically present*. Family vault, estate/break-glass documents, journalist source material, two-person-rule handoffs. Threshold crypto exists in HSMs; **bound to consumer physical co-presence, it does not exist** — and "phones near each other" is literally Jetzy's core competence.

**9. Shake-to-pair.**
Hold two phones together and shake: correlated accelerometer traces become the shared secret / pairing proof ("Shake Well Before Use," Mayrhofer & Gellersen 2007 — famous research, never productized). Solves the B3/B4 auth gap with zero dialogs, on-brand with the gate-free philosophy: security that feels like a handshake, not a checkbox.

**10. Proof-of-handover receipts.**
Once AEAD lands (B2), the manifest + per-file CRC transcript is one signature away from a **non-repudiable delivery receipt** both sides retain: what bytes, what hash, when, to which key. "Registered mail" for local transfer — legal documents, journalism, chain-of-custody. No server, no notary; the ledger already holds the evidence.

### Theme 5 · One-to-many & ambient

**11. Local swarm — classroom mode.**
One sender, thirty receivers: receivers re-share verified chunks to each other so the sender's link stops being the bottleneck (BitTorrent piece-map logic, but ad-hoc local). Teacher hands out a worksheet pack; press kit at an event; game night APK. The per-file CRC + chunk ledger is already most of a piece map. SHAREit's group mode is sequential unicast; a true local swarm is unshipped territory.

**12. Wi-Fi Aware billboard — the local drop box.**
Publish a parcel to *anyone in range* for N minutes: menu, schedule, conference deck, open-mic mixtape. Aware publish/subscribe is built precisely for this and sits unused industry-wide; Jetzy has Aware managers on Android and the iOS-26 bridge. Pair with WebReceiver for the no-app fallback ("join hotspot, open the page"). AirDrop meets bulletin board.

### Theme 6 · The other side needs nothing

**13. Browser-only peer, everywhere.**
Finish `WebReceiver` (B12) and promote "no app installed" to a first-class peer: QR → URL → any phone/laptop browser uploads/downloads over the local socket. Then compose with idea 1: on a machine with no usable network (corporate lockdown), the *web page itself* renders/reads fountain QR via the webcam. Every transfer with a non-user becomes an acquisition moment that works. LocalSend/Snapdrop do same-LAN browser transfer; hotspot-hosted + capability-negotiated + optical-fallback is a different league.

### Quick hits (smaller, still unshipped)
- **Delta transfer:** rsync rolling-hash against a file the receiver already has — re-send only changed blocks of the edited video/PSD. Consumer transfer apps never do this; the chunked ledger makes it natural.
- **Capability-negotiated transcoding:** receiver low on space / incompatible format → negotiate HEIC→JPEG or 1080p at manifest time. The negotiation layer exists; this extends it from transports to *content*.
- **Cross-OS clipboard pipe:** a standing tiny-payload channel between your own paired devices — Universal Clipboard, but Android↔iOS↔desktop. The smallest quantum of file transfer, used 20× a day.
- **Point-to-send ranging:** ultrasonic time-of-flight + RSSI fusion to default-select the peer you're *facing* (cross-platform answer to UWB NameDrop).

### The three bets I'd place

1. **Deliver-on-meet queue (#6)** — changes what "send" *means*; daily-habit feature, and it forces the pairing/identity work (B3) that everything else needs anyway.
2. **Full-duplex optical + browser fountain (#1 + #13)** — genuinely unprecedented, demo that markets itself, and it owns the air-gap/corporate-lockdown niche no one can follow quickly.
3. **Channel bonding (#4)** — the visible proof that the negotiator brain is real; turns the architecture's hidden sophistication into a benchmark bar chart nobody else can show.

---

## Part B — Third-pass audit

Three parallel verification agents over HEAD + working tree; everything below is code-verified and **new** (not in JETZY_DEEP_REVIEW.md or PERFORMANCE_AUDIT.md).

> **Status 2026-06-10 (same day):** Part B has been implemented on `master` in the commit series
> `8cd1bf3..6538504` — protocol v4 sequential bidi (B47 closed), durable per-item auto-save,
> the lifecycle/FGS/WifiLock batch, the iOS retry-for-all-failures batch (ships the race-dialog
> WIP), the UX batch (radar taps, ladder skip, hotspot retry, gate-cancel), release hygiene,
> the dead-code/strings sweep, the product adds (share target, open-after-receive, clipboard,
> DnD, persisted prefs + editable device name), and 19 new hardening tests (suite: 98/98).
> Deliberately deferred: iOS share extension (new Xcode target), engine message-key i18n
> refactor, in-app received-files browser (needs a transfer-history layer), enabling R8
> (rules staged), the M3-alpha and `-Xskip-prerelease-check` pins, and full semantics/i18n
> passes. iOS backgrounding (`beginBackgroundTask` grace) remains the top open lifecycle item.

### B.1 Resolution status of B1–B47 after v0.5.0

Of the 9 fixes claimed by `829d5b8`: **8 are real** (B1, B5, B9, B19, B20, B28, B30, B31). **B29 is partial** — handshake + receiver-manifest reads got timeouts, but the **sender's `readManifestAck` is still unbounded** (`P2PManager.kt:447`) and the sender watchdog starts only after it: a peer that handshakes then goes silent wedges the sender forever. B6/B7/B10 are partially improved. **35 of 47 remain open**, including all High-severity security items (B2/B3/B4) and:

> **B47's risk is now elevated, not just open:** the gate-free flow makes both-sides-staged a *mainstream* case, and the loser of the `DirectionResolver` tiebreak still silently never sends its staged files (`DirectionResolver.kt:44`, `P2PManager.kt:259-262`). This is the most urgent correctness item in the app.

Full sweep: B2,B3,B4,B8,B11–B18,B21–B27,B32–B46 confirmed still open at their (drifted) locations; details in the deep review stand.

### B.2 New findings — FIX

**The auto-save isn't durable yet, but the UI/teardown act as if it were** (theme of the top three):

| Sev | Finding | Where |
|---|---|---|
| HIGH | **"Done" during auto-save cancels the move mid-copy and purges the files** — `transferComplete=true` is set *before* `autoSaveReceivedFiles()`; Done has no `enabled=!isSaving` guard (manual Save does), and `cleanup()` cancels the in-flight copy then purges temps. B1's ghost survives in a race window the size of a multi-GB MediaStore copy. | `P2PManager.kt:823,828`; `TransferScreenUI.kt:295-311` |
| MED | **Partial save failure strands the tail and breaks the manual fallback** — batch is one `runCatching`; failure at file k+1 leaves 1..k's temps already deleted, `finalizeReceivedFilesAt` then throws on the first missing temp on every retry, and Done purges the never-saved rest. Files lost while the app says "save failed." | `ReceiveSave.android.kt:22-62`; `P2PManager.kt:1050-1070` |
| MED | **Mid-batch failure never auto-saves the files that already verified** — catch path sets `transferComplete=true` without saving; button renders a safe-looking "Done" that purges CRC-verified files. | `P2PManager.kt:828-835`; `TransferScreenUI.kt:310` |
| MED | **Sender manifest-ack read unbounded** (the B29 gap above) — wrap like the other two reads. | `P2PManager.kt:447,471` |
| MED | **Oversized TEXT (>1 MB) is received, verified, then permanently inaccessible** — both save paths filter `EntryType.FILE` only, no preview (`textContent=null`), temp purged. The B19 fix's own comment claims otherwise. | `P2PManager.kt:782-784,1018,1049` |
| LOW | **ManifestAck resume fields are unvalidated remote input** — hostile `resumeFileIndex/byteOffset` → IndexOutOfBounds / spurious shrink-abort. B20's mirror, sender side. Clamp or treat as OK(0,0). | `JetzyProtocol.kt:240`; `P2PManager.kt:474-481` |
| LOW | **Manifest bound limits count, not bytes** — 1M entries × five ≤1MB strings: timeout×linkspeed is the only memory ceiling. Add aggregate budget (16–64 MB) and drop cap to ~10k. | `JetzyProtocol.kt:294` |
| LOW | **`offeringFiles` read twice across a blocking read** — HELLO value vs resolver value can diverge if staging mutates during the 15s window → peers resolve opposite directions. Capture once. | `P2PManager.kt:859` + resolve site |
| LOW | **TEXT temps leak after successful FILE auto-save** — `purgeUnsavedReceivedFiles` early-returns on `saveComplete`. | `P2PManager.kt:334,1018` |

**Lifecycle & backgrounding (previously unaudited dimension):**

| Sev | Finding | Where |
|---|---|---|
| HIGH | **No `onCleared()` — swiping the task away orphans session + leaks the FGS** ("Jetzy is transferring" forever; `stopBackgroundService()`'s only call site is inside `cleanup()`). | `JetzyViewmodel.kt`; `P2PManager.kt:293` |
| HIGH | **`START_STICKY` resurrects a zombie FGS after process death** — re-runs `startForeground` + fresh 30-min wakelock with no manager alive and no stop path. Use `START_NOT_STICKY` / stop-self grace. | `JetzyForegroundService.kt:58` |
| HIGH | **iOS has no backgrounding story at all** — no `UIBackgroundModes`, no `beginBackgroundTask`, no scenePhase observer; home-press/lock suspends sockets and the peer's 8s watchdog kills the session. Even the free ~30s grace task is unused. | `iosApp/Info.plist`; `iOSApp.swift` |
| MED | No `WifiLock` — CPU stays awake, Wi-Fi radio doesn't (screen-off power-save throttles the link the FGS exists to protect). | `JetzyForegroundService.kt:69` |
| MED | 30-min wakelock hard cap lapses mid-long-transfer; no re-acquisition. | `JetzyForegroundService.kt:73` |
| MED | `configChanges` misses `uiMode`/`density`/`smallestScreenSize` — scheduled dark-mode flip mid-transfer recreates the Activity: loses KEEP_SCREEN_ON, managers hold a dead Activity (permission requests silently no-op), discovery `LaunchedEffect` re-runs and **binds a second server socket** (`stopDiscoveryAndAdvertising` never closes `serverSocket`). | `androidApp AndroidManifest:15`; `LanMdnsP2PM.kt:104-110` |
| MED | iOS `isIdleTimerDisabled = true` globally at App.init, never reset — screen never auto-locks even idling on the menu. Scope to active transfer. | `iOSApp.swift:7` |
| MED | Desktop window close mid-transfer = instant silent kill — no confirm, no abort frame, temps left behind. | `desktop Main.kt:27` |
| MED | **Wi-Fi loss during mDNS discovery leaves stale peers and hides the escape hatch** — no connectivity callback; "Try a different transport" is gated on `availablePeers.isEmpty()`. | `PeerDiscoveryScreen.kt:107-114` |
| MED | **iOS Local Network denial silently swallowed** — all NetService failure delegates are stubs; eternal empty radar, no Settings hint (B25's pattern on the *default* transport). | `appleMain/LanMdnsP2PM.kt:210,246` |
| LOW | BT adapter-off during discovery unnoticed (no `ACTION_STATE_CHANGED`); resume state is process-lifetime only (orphaned cache partials after restart). | `BluetoothSppP2PM.kt:187`; `P2PManager.kt:175` |

**Release readiness:**

| Sev | Finding | Where |
|---|---|---|
| HIGH | **Nobody but this machine can build HEAD** — `mavenLocal()` for `io.github.yuroyami.kmpssot:1.3.1`, which carries the app's whole identity (version/appId/icons). The uncommitted `settings.gradle.kts` edit removes `mavenLocal()` — verify the plugin is actually live on the Portal before committing, else even this machine breaks on next cache clean. | `settings.gradle.kts:4`; `build.gradle.kts:2` |
| MED | iOS version drift: pbxproj `MARKETING_VERSION 0.4.1` vs kmpSsot `0.5.0` (About dialog vs TestFlight will disagree); plus a third deployment target (15.6) in the same file. | `project.pbxproj:380,409` |
| MED | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` declared *and* fired as a gate requirement — Play-restricted permission, same review-bait family as the fixed B9, redundant with the FGS. | `androidMain AndroidManifest:31`; `AndroidPermissionRequirements.kt:181` |
| MED | R8 fully off, no `proguard-rules.pro` anywhere — fine today, a trap the day it's enabled (ktor/koin/serialization keep rules). | `androidApp/build.gradle.kts:47` |
| MED | Release path on pre-release artifacts: M3 `1.11.0-alpha07`; desktop builds with `-Xskip-prerelease-check`. | `libs.versions.toml:18` |
| MED | Desktop `packageVersion "0.5.0"` violates DMG/MSI major>0 rule — distributables have likely never been produced; no signing/notarization config either. | `desktopApp/build.gradle.kts:40` |
| LOW | Signing reads `File("local.properties")` relative to daemon CWD — silently null creds off-root (CI). Use `rootProject.file(...)`. | `androidApp/build.gradle.kts:29` |
| LOW | `ITSAppUsesNonExemptEncryption=false` is correct **today**; flips when B2's AEAD lands — note it in the security work. | `Info.plist:86` |

**Product surface:**

| Sev | Finding | Where |
|---|---|---|
| HIGH | **Radar peer dots are stacked full-size clickables — taps select the wrong peer** (every dot's hit target is the whole radar; last-composed wins). Also N overlapping unlabeled targets for screen readers. | `PeerDiscoveryScreen.kt:418-443` |
| MED | **"Cancel transfer" wipes the staged tray; back-press on the same screen preserves it** — `resetEverything()` vs `cancelDiscovery()` inconsistency; cancel a wrong-peer connect → re-pick 30 files. | `TransferScreenUI.kt:117-121,296-300`; `JetzyViewmodel.kt:249-281` |
| MED | **"Try a different transport" first re-runs the transport that just failed** (rung 0 = mDNS = the default that already failed), and ladders carry duplicate rungs on common hardware (non-NAN Android: WiFiDirect twice; non-NAN iPhone: LanWifi twice). | `JetzyViewmodel.kt:167-174`; `MainActivity.kt:96-98`; `main.kt:51-54` |
| MED | **Android host-QR dead-ends on hotspot failure** — eternal "Starting hotspot…" spinner; the `refreshor` retry state exists but nothing increments it. | `QRDiscoveryScreen.android.kt:78-83`; `HotspotP2PM.kt:76-91` |
| MED | **Cancelling the permission gate during a fallback switch strands a dead discovery screen** — new manager cleaned, no nav/state reset, rung consumed. | `JetzyViewmodel.kt:178-218` |
| MED | Engine layer hardcodes ~14 user-facing English sentences `stringResource` can't reach (snacky/status in P2PManager/Viewmodel) — needs message-key indirection, design before the catalog grows. | `P2PManager.kt:451-1079` passim |
| MED | Desktop QR screen skipped string-resources entirely (~14 literals), including copy that still says "choose to send/receive" — a gate v0.5.0 deleted. | `QRDiscoveryScreen.desktop.kt:96-303` |
| MED | RTL disabled on Android only (`supportsRtl="false"`) while Compose iOS/desktop mirror from locale — three platforms, two layouts on day one of any translation. | `androidApp AndroidManifest:10` |
| MED | Zero semantic markup repo-wide (no `Modifier.semantics`/`Role.`/live regions): selection conveyed by color only, completion announces nothing. | commonMain UI |
| LOW | Receiver never sees their own advertised name ("you're visible as ⟨name⟩") — compounds B17's same-model collisions. | `PeerDiscoveryScreen.kt:118` |

### B.3 WIP review — the uncommitted iOS QR race dialog

**Verdict: coherent and safe to ship, but it under-delivers on B6.** The `joinRaceDetected` state machine is clean (set in one place, cleared on every entry/success/retry/dismiss; no stuck-true path; spinner ordering explicitly handled; "Try again" genuinely re-runs the full join via `retryConnect()` → `joinWithRetry()`). Before committing:
1. **Point the two lying snackbars at the new mechanism** — "Couldn't join… **Tap Retry** to try again" and "Couldn't reach sender… **Tap Retry**" (`LanWifiP2PM.kt:98,111`) fire on a screen with no Retry button; `retryConnect()` now works for *any* cached-QR failure, so route the common TCP-timeout failure through the same dialog (that's the actual B6 ask).
2. Dialog-dismiss still lands on a **dead viewfinder** (`stopRunning()` never restarted; B6's camera leg untouched).
3. Add defensive clears of `joinRaceDetected`/`lastQrData` in `cleanup()`/`prepareForResume`, and delete the orphaned `ios_hotspot_autojoin_tip` string in the same commit.

### B.4 ADD (high-value, small, seams already exist)

1. **Android share-sheet target** (ACTION_SEND/SEND_MULTIPLE → `elementsToSend`) — Jetzy reachable from Gallery/Files/Chrome; the two-click goal achieved from *outside* the app. Cheapest distribution win in the codebase. (`AndroidManifest:20-23`; `MainActivity.kt:40`)
2. **"Open" / "Show in folder" on success** — Android already creates and *discards* the MediaStore URIs (`ReceiveSave.android.kt:47-59`); desktop knows the dir; today's success state is a dead end.
3. **Received-files screen** over the deterministic auto-save dir (`Downloads/Jetzy`) — history without a persistence layer; gives the app a reason to open when not transferring.
4. **Minimal settings**: persist `nightMode` (currently resets every launch, `JetzyViewmodel.kt:233`) + editable device name (also mitigates B17 and the "which one is you?" gap). Needs one small KMP key-value dep — none exists today.
5. **"Send clipboard" chip on home** — paste-to-send exists but is buried 4 levels deep (`PickTextSubscreen.kt:202`). The "beam this link/OTP to my other device" case becomes two clicks.
6. **Desktop drag-and-drop** onto the window (`Modifier.dragAndDropTarget`) — desktop-native share sheet equivalent.
7. (Later, heavier) iOS share extension — needs a new Xcode target + app-group handoff.

### B.5 REMOVE (beyond deep-review §9)

- **36 dead string resources — but ~20 of them are the localization of strings the UI hardcodes** (the gate strings are dead-dead; `files_of_total`, picker snacks, plurals etc. should be **wired, not deleted**). Two dead imports ride along (`wifi_direct`, `excluded_images`).
- `P2pUiState.kt` (zero refs), `currentPeerPlatform`/`peerPlatform` in the viewmodel (writes only — v0.5.0 leftovers).
- Dead composeResources shipped in every binary: `jetzy_raster.png`, `jetzy_raster_withbg.png`, fonts `broshk4blue/digitalorange/genos.ttf`.
- No-op build plugins: `kotlinx-serialization` (zero `@Serializable`), `ksp` (zero processors); dead catalog entries `media3`, `uriKmp`, `compose-unstyled`, `skie`, `kSerialization`, unused `compose-desktop` alias.
- Four dead nav routes (picker subscreens registered in `AdamScreen.kt:218-221`, only ever rendered via the pager); `refreshor` (or better: wire it as the hotspot retry).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (see release table).

### B.6 Test gaps (73 tests exist, all pure-logic; the dangerous untested seams)

1. **v0.5.0's manifest-DoS bounds** — brand-new security code, zero hostile-input tests; a regression silently reopens B20.
2. **`sendFiles`/`receiveFiles` end-to-end** over in-memory channels — the harness pattern already exists in this repo (`WebReceiverServeTest`); cover resume-ACK honoring, B5 short-read abort, timeouts, auto-save trigger.
3. Handshake → `DirectionResolver` → `currentOperation` wire seam (the thing the gate-free flow now fully trusts).
4. Viewmodel ladder/state machine (B28 clamp, `pendingProceed`).
5. `autoSaveReceivedFiles`/`finalizeReceivedFilesAt` composition (would have caught the oversized-TEXT stranding).

### B.7 Suggested order

1. **Auto-save durability trio** (Done-race, partial-failure, failure-path save) — it's the v0.5.0 flagship feature; right now it can still lose files in exactly the way B1 used to.
2. **B47** — the gate-free flow promoted silent data-drop to a mainstream path; minimum: surface "your files weren't sent," real fix: `DirectionResolver.BOTH` (§2.3 design is ready).
3. **Sender ack timeout** (one-line B29 completion) + ManifestAck clamps — finishes the DoS-hardening story actually claimed by v0.5.0.
4. **Ship the WIP** with the two snackbar→retry reroutes + orphaned string deletion.
5. **Lifecycle batch**: `onCleared` + `START_NOT_STICKY` + WifiLock + radar hit-targets (functional mis-selection, not just a11y).
6. **kmpssot publish verification** before committing the `settings.gradle.kts` change.
7. Then the adds (share-target first) and the remove sweep, and hostile-manifest tests before any further wire changes.
