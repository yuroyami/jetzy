# Architecture & Flow Audit — Jetzy

_Generated 2026-06-05 by a 5-agent architecture audit (transport/negotiation · discovery/bootstrap · data-plane protocol · end-to-end UX · permissions/lifecycle), cross-verified against source. This is the **design** counterpart to `PERFORMANCE_AUDIT.md` — it is about flow, friction, and the path to a breakthrough, not micro-perf (that pass already landed)._

---

## The one-sentence verdict

> **Jetzy already contains a complete, tested, elegant transport-negotiation brain — and it is wired to nothing. Almost every "overcomplication" in the app is the code asking the *user* to answer a question the *code already has the data to answer itself.* The breakthrough is mostly subtraction.**

The app is ~14k LOC, KMP across Android/iOS/desktop/macOS, with a genuinely strong foundation: a **transport-agnostic byte-stream protocol**, a **deterministic zero-round-trip negotiator**, and a **transport-independent resume checkpoint**. Three latent superpowers, all built, all dark. Wire them together and the app does things AirDrop and Quick Share structurally *cannot* — because those are single-vendor and Jetzy is not.

---

## Part 1 — What's overcomplicated (the diagnosis)

Every friction point below is the same root cause: **a decision outsourced to the human that the machine could make.**

### 1.1 The user must hand-pick the peer's *platform* — and that single guess *is* the entire transport choice
- The home screen forces a tap on **Android / iOS / PC** before you can proceed ([MainScreen.kt:201](shared/src/commonMain/kotlin/jetzy/ui/main/MainScreen.kt), gated at `:85-89`).
- That manual pick is fed straight into a hand-coded `when(peerPlatform)` that picks the transport — duplicated **three times**, once per platform ([MainActivity.kt:84](shared/src/androidMain/kotlin/jetzy/MainActivity.kt), [main.kt:41](shared/src/iosMain/kotlin/main.kt), `desktopApp/.../Main.kt:50`), each with copy-pasted prose admitting the deadlock isn't solved: _"there's no pre-pairing handshake to find that out."_
- On iOS, App Store anonymization renders both non-self platforms as an **identical "Another platform" button** — the user literally chooses between two indistinguishable icons ([PlatformUtils.kt:28](shared/src/commonMain/kotlin/jetzy/utils/PlatformUtils.kt)).
- A wrong guess strands one side (e.g. iPhone waiting on an empty Wi-Fi Aware cluster while the Android hosts a hotspot nobody joins).

### 1.2 Send-vs-Receive is a hard gate, declared *before you know who you're talking to*
- The very first tap is a role mode-switch ([OperationButton.kt:54](shared/src/commonMain/kotlin/jetzy/ui/main/OperationButton.kt)), re-enforced at Proceed. AirDrop never asks this — you pick a file + a target, or you're simply receivable.

### 1.3 Transport selection is a guess with **no automatic fallback**
- The only recovery from a bad transport is a manual **"Try a different transport"** button, which only appears after a hardcoded **6-second** empty timeout ([PeerDiscoveryScreen.kt:107](shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt)), walking a fixed ladder one rung per tap ([JetzyViewmodel.kt:151](shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt)).

### 1.4 Received files are **not saved** — and silently deleted if you miss the step
- After a transfer the receiver must tap **Save → pick folder → Done** ([TransferScreenUI.kt:216](shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt)). Files sit in a temp dir and are **purged on teardown** ([P2PManager.kt:241](shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt)). Tap "Done" without "Save" and everything you just received is gone.

### 1.5 Four separate file pickers for one intent
- Files / Photos / Videos / Text each have their own tab, FAB, and OS-picker session ([ElementPickingScreen.kt:22](shared/src/commonMain/kotlin/jetzy/ui/filepicking/ElementPickingScreen.kt)) — yet the backend merges them into one list immediately ([JetzyViewmodel.kt:220](shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt)). Mixed selection ≈ 9 interactions where a unified sheet would be ≈ 3.

### 1.6 The QR screen is keyed on *local platform, not role* — a latent correctness trap
- Android **always** shows a QR; iOS **always** scans ([QRDiscoveryScreen.android.kt:82](shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt), [QRDiscoveryScreen.ios.kt:72](shared/src/iosMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.ios.kt)). Two Androids both display (neither scans); two iPhones both scan (neither hosts). The pairing only works cross-platform *by accident of which platforms happen to do which*.

### 1.7 Dead weight in the platform-glue layer
- `P2pAppleHandler.kt` is **~530 lines of commented-out legacy code** wrapping one live `data class` ([P2pAppleHandler.kt:383](shared/src/iosMain/kotlin/jetzy/p2p/P2pAppleHandler.kt)).
- `Platform.canScanQR` is defined per-platform with **zero usages**.
- `NearbyConnections` is registered with `quality=86` and advertised in the capability mask but has **no manager** — it inflates the advert for a transport that can never run.
- Android declares `READ/WRITE_EXTERNAL_STORAGE` but never requests them (SAF/FileKit is used) — Play Console review bait.

---

## Part 2 — The latent superpower (built, tested, dark)

This is what makes the breakthrough cheap. Three assets already exist:

### 2.1 A deterministic, zero-round-trip negotiator
`TransportNegotiator.negotiate(local, remote)` ([TransportNegotiator.kt:53](shared/src/commonMain/kotlin/jetzy/p2p/TransportNegotiator.kt)) intersects two capability bitmasks and returns **every mutually-supported transport, best-first by `quality`, each tagged with this device's role** (HOST/CLIENT). It is:
- **Pure** — no I/O, no sockets. Just two `CapabilityProfile`s in, a ranked list out.
- **Symmetric & antisymmetric** — `hostAffinity()` + a `tiebreak()` keyed on `(platform, name, caps)` mean **both peers independently compute the identical plan with zero coordination messages** ([TransportNegotiator.kt:93](shared/src/commonMain/kotlin/jetzy/p2p/TransportNegotiator.kt)).
- **Fully unit-tested** (`TransportNegotiatorTest.kt`, 8 cases).
- **Called by nothing** outside tests. Production references are KDoc comments only.

### 2.2 The capability data is *already on the wire* — and thrown away
- **In the QR:** `QRData.capabilities` is a hex bitmask, produced ([HotspotP2PM.kt:143](shared/src/androidMain/kotlin/jetzy/managers/HotspotP2PM.kt)) and parsed ([QRData.kt:59](shared/src/commonMain/kotlin/jetzy/models/QRData.kt)) — but **no scanner reads it**. The iOS scan handler hands `qrData` to `establishTcpClient`, which consumes only `sessionId`/host/port.
- **In the handshake:** `HelloFrame.capabilities` is exchanged on **every** connection, then fed to `logPeerCapabilities`, which computes the mutual set and... **writes it to a log** ([P2PManager.kt:752](shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt)). Its own comment: _"Diagnostic-only for now… When the upgrade logic lands it'll consume the same intersection."_ The upgrade logic was never built.

### 2.3 A transport-independent resume checkpoint + a swappable data plane
- The protocol runs over **any duplex byte stream** — it depends only on a Ktor `ByteReadChannel`/`ByteWriteChannel` pair, never a socket. TCP transports set `connection`; non-TCP transports (MPC, Wi-Fi Aware, Bluetooth SPP) bridge native streams into in-process channels and call `startTransferWithChannels(input, output)` ([P2PManager.kt:89](shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt)).
- Resume is a **content-addressed checkpoint** keyed by `(sessionId, fileIndex, byteOffset)` — `receiverLedger` ([P2PManager.kt:279](shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt)), written once per file in a `finally` so a mid-file drop records accurate progress; `computeResumePoint()` finds the first incomplete file; the CRC is rebuilt over the on-disk prefix so split-hashing is byte-identical. **None of it references the transport type.** `prepareForResume()` already preserves exactly `sessionId` + ledger + staged files while tearing down the channel.

**Put 2.1 + 2.2 + 2.3 together and you have everything needed to negotiate the best link with zero round-trips, race transports, and swap the data plane mid-stream — without inventing anything.**

---

## Part 3 — The breakthrough ("hacks in the matrix")

Six moves, ordered from "wire what exists" to "no risk no gain." Each notes how *grounded* it is and what needs on-device validation (the owner validates on real hardware; none of this was run on radios here).

### 🪄 HACK 1 — Zero-handshake negotiation: *"the QR already knew"*
**Move:** the moment the camera decodes the QR, the scanner has *both* capability halves. Build `CapabilityProfile.local()`, run `TransportNegotiator.best(local, remote)`, and connect over the chosen medium — **before any radio is touched.** There is no "connecting… deciding transport" phase because the decision happened during the 50 ms of decoding.
**Why it's magic:** you point your camera and you're *already* on the fastest mutual link. The negotiation was invisible because it had no round-trips.
**Grounded:** 100%. The QR carries `capabilities` ([QRData.kt:31](shared/src/commonMain/kotlin/jetzy/models/QRData.kt)); the negotiator exists. Missing wire: scanner reads `qrData.capabilities` instead of dropping it. **Low risk.**
**Bonus:** make the QR a *multi-homed business card* — carry mDNS service name + hotspot creds + Wi-Fi Aware id together, so the scanner connects over whichever the negotiator picked.

### 🧠 HACK 2 — Telepathic roles: *"they agreed without speaking"*
**Move:** delete the "pick the peer's platform" tap and the "Send/Receive" mode entirely. Both devices exchange capabilities out-of-band (QR or advert) and **independently compute the same `(transport, who-hosts)` plan** — no negotiation messages.
**Why it's magic:** two phones from two vendors arrive at the identical connection plan as if they read each other's minds. Role falls out of `hostAffinity` + `tiebreak`, not a user choice.
**Grounded:** the symmetry is already proven in `TransportNegotiatorTest`. Missing: the advert must carry caps+platform (today the mDNS TXT record is empty and `P2pPeer` carries only a name + a **hardcoded** `signalStrength=3`). **Low-medium risk.**

### 🏁 HACK 3 — Happy-Eyeballs transport racing: *"it never fails to connect"*
**Move:** instead of one transport with a 6 s timeout then a manual retry, **stagger-launch the top 2–3 ranked transports in parallel** (RFC 8305 for radios). First to yield a live byte channel wins; cancel the losers; hand the winner to the protocol. The data plane is already transport-agnostic, so the winning channel just flows into `startTransferWithChannels`.
**Why it's magic:** connections simply *succeed*, fast — the user never sees a spinner stall or a "try another transport" button. A mis-ranked transport costs nothing because a better one was racing alongside it.
**Grounded:** the ranked list (`negotiate()`) and the agnostic sink already exist; this replaces the manual `switchToNextFallbackTransport` ladder. **Medium risk** (parallel radio bring-up needs device testing — e.g. don't start two Wi-Fi-disrupting transports at once; stagger by quality).

### 🚀 HACK 4 — The gear-shift: *"the transfer got faster mid-stream"* — **the crown jewel**
**Move:** connect **instantly** over the always-works link (mDNS / hotspot / eventually BLE), **start moving bytes immediately**, and transparently **upgrade to a high-bandwidth link (Wi-Fi Aware / Direct) mid-transfer** — resuming the exact byte offset over the new pipe via the existing resume machinery. The user sees **one continuous progress bar that simply accelerates.**
**Why it's magic:** the transfer never stops, never restarts — it *shifts gears*, like an automatic transmission. "How is this even working?" — because the receiver's ledger doesn't care whether a pause-and-continue came from a crash or a deliberate lane-change; **it's the same code path.** The resume mechanism, built for crash recovery, is repurposed as a live transport swap.
**Why it dissolves the chicken-egg entirely:** you stop needing to *pick the right transport*, because you **start on the universal one and climb to the best available** — automatically, without interrupting anything. Selection stops being a prerequisite and becomes an optimization that happens in the background.
**Grounded — spookily so:** resume is transport-independent and keyed by `(session, file, offset)`; `startTransferWithChannels` swaps the channel without touching `connection`; capabilities are exchanged in HELLO; `prepareForResume` already preserves precisely the right state; there's even a comment promising this exact feature ([P2PManager.kt:747](shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt)). Missing: **one in-band frame** — an `UPGRADE(transport, endpoint)` control message — and the coordinator glue to stand up the second link while the first keeps flowing. **Higher risk / needs device validation:** the open question is whether each OS lets you hold the cheap link *while* bringing up the fast one (e.g. can iOS keep a hotspot/LAN socket alive while Wi-Fi Aware attaches?). That's the first thing to prove on hardware.

### 🌐 HACK 5 — App-less receive: *"I sent it to a laptop that only has a browser"* — the cross-platform killer
**Move:** the host already binds a TCP server. Teach that **same socket** to also speak minimal HTTP, and let the QR also encode a `http://ip:port` URL. A peer with **no app** — any browser — receives the files. One socket serves both protocols by **peeking the first 4 bytes**: `"GET "` → serve the web receiver; the Jetzy magic `0x4A45545A` → run the native protocol. One port, two worlds, auto-detected.
**Why it's magic:** it attacks the real reason cross-platform sharing fails in practice — **install friction**. Snapdrop-style reach, bootstrapped by the same QR, with the native app as the fast path when both sides have it.
**Grounded:** there's already a TCP server (`LanHostP2PM`/`HotspotP2PM`) and a `Platform.Web` enum that's currently unimplemented ([P2pTechnology.kt:201](shared/src/commonMain/kotlin/jetzy/p2p/P2pTechnology.kt)). The protocol-peek multiplex is a small, self-contained addition. **Medium risk** (new surface, but isolated).

### 🌈 HACK 6 — Radio striping / channel bonding: *"one file, three radios at once"* — moonshot
**Move:** don't *switch* transports — use several **simultaneously**, striping byte ranges across BLE + Wi-Fi Aware + Bluetooth like multipath TCP / BitTorrent-across-radios. The resume ledger already tracks per-offset progress; extend it to per-range.
**Why it's magic:** aggregate bandwidth across every radio the pair shares. The "how is this working?!" ceiling.
**Grounded:** conceptually supported by the per-offset ledger, but requires **range-request framing** (the protocol is currently sequential whole-file) — a real protocol change. **Highest risk. Present as a research spike, not a near-term commitment.** This is the "no risk no gain" tier.

### Table-stakes seamlessness (do alongside HACK 1–2)
- **Auto-save** received files to Downloads/Photos, show "Saved · Open" — folder picker becomes an optional override, not a mandatory gate. (Kills 1.4 + the silent-deletion footgun.)
- **One unified multi-select picker** for all types. (Kills 1.5.)
- **Predictive pre-warm:** because negotiation is zero-round-trip, open the data channel the instant the QR decodes — before the user confirms what to send — so the pipe is hot by the time they tap. Latency hidden entirely.

---

## Part 4 — Sequencing (safe, high-impact first)

The dependency order is natural: each step makes the next cheaper, and the first three are mostly *wiring existing, tested code* — low risk, validatable without exotic hardware.

1. **Wire the negotiator into the QR path (HACK 1).** Scanner reads `qrData.capabilities` → `negotiate()` → connect via the chosen medium. Delete one of the three `when(peerPlatform)` tables. _Biggest friction win per line changed; the engine is already tested._
2. **Carry caps+platform in the discovery advert + collapse the role/platform UX (HACK 2).** Put the mask in the mDNS TXT record and on `P2pPeer`; drop the manual platform pick and the up-front Send/Receive gate.
3. **Auto-save + unified picker (table stakes).** Independent of the transport work; pure UX wins.
4. **Happy-Eyeballs coordinator (HACK 3).** Replace the manual fallback ladder with a staggered race over `negotiate()`'s ranked list.
5. **Prove the gear-shift on hardware (HACK 4).** Spike the one open question — can each OS hold the cheap link while attaching the fast one? — then add the `UPGRADE` frame. _This is the signature feature; de-risk it early but ship it after 1–4._
6. **App-less web receive (HACK 5)** and **striping spike (HACK 6)** as parallel bets.

---

## Part 5 — Open questions for the next subagent dive (need device/research validation)

These are the genuine "rift of knowledge" edges — worth a focused research/hardware spike each:
- **Gear-shift feasibility per OS:** can iOS keep a `NEHotspotConfiguration`/LAN socket alive while Wi-Fi Aware attaches? Can Android hold mDNS-over-Wi-Fi while Wi-Fi Direct negotiates a group? (Determines whether HACK 4 is "swap" or "must tear down first.")
- **Parallel radio bring-up safety (HACK 3):** which transport pairs conflict if started simultaneously (anything that toggles the station interface)? Defines the stagger policy.
- **Web-receiver scope (HACK 5):** chunked HTTP download of a multi-file manifest as a zip stream vs. individual files; how to do upload *from* a browser back to the app.
- **The vestigial protocol verbs** (`AckStatus.REJECTED`, `FileAck.CANCELLED` are handled but never sent) and the strict `VERSION` equality gate — fold a real version-negotiation into the same `capabilities` extensibility the negotiator already relies on.

---

_The headline finding, restated: the app's hardest problem (transport selection) is already solved in `commonMain` — it just isn't plugged in. Plug it in, then repurpose the resume engine as a live gear-shift, and Jetzy does something no single-vendor app can. That's the breakthrough, and it's mostly subtraction plus one clever frame._
