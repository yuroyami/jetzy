# Jetzy — Deep Review & Breakthrough Design

*Definitive review for the owner. Every claim was re-verified against the current source on `master` (through `ba6dbf5`); where the 8-day-old `ARCHITECTURE_AUDIT.md` is now stale, this document says so explicitly. File:line references are live.*

---

## 1. Executive verdict

**Jetzy's engine is better than its app.** The hard parts — a transport-agnostic data plane, a pure symmetric negotiator, content-addressed resume, zip-slip-safe finalize, a clean length-prefixed protocol — are genuinely well-built and unit-tested. The problem is that the polished brain is **decapitated from the body**: the negotiator, the Happy-Eyeballs coordinator, the capability→manager factory, and the QR/mDNS capability fields are all implemented yet drive *nothing* on the live connection path, which still runs through three hand-coded `when(peerPlatform)` tables. Meanwhile the UI is one architecture-generation behind its own engine — it forces a Send/Receive pick and a peer-platform pick that the protocol already makes redundant, and it ships a **silent data-loss bug** (a received file is deleted if you tap "Done" before "Save"). Against the owner's four priorities: **(1) bug-free** — dented by the data-loss purge, a sender-truncation hang, several frozen-screen dead-ends, and zero encryption; **(2) seamless** — blocked by two redundant up-front gates and a 5–6 tap cold start; **(3) speed** — left on the table because the negotiator's better transport choice is computed and then ignored; **(4) UI** — visually strong but with fabricated signal bars, mislabeled transports, and broad accessibility gaps.

**The single biggest opportunity:** wire the existing pure brain into the live path. Consuming `qrData.capabilities` / the mDNS TXT record at *discovery* time and racing transports through the already-built `getManagerForTechnology` collapses the platform pick, the Send/Receive pick, and the speed gap **all at once** — and it is mostly wiring of already-tested code, not new invention.

---

## 2. The two-click, role-free, bidirectional north-star

### 2.1 The target flow

The protocol is **already role-free**. `DirectionResolver.resolve()` (`DirectionResolver.kt:38-45`) derives who-sends purely from each peer's `offeringFiles` intent, and `beginTransfer()` (`P2PManager.kt:226-255`) runs the symmetric HELLO and then *overwrites* `currentOperation` with the resolved direction. The Send/Receive toggle and the platform picker are **UI-layer fictions** living in `MainScreen.kt` and `proceedFromMainScreen(peerPlatform, operation)` (`JetzyViewmodel.kt:129-130`). The wire already supports deleting both.

The home screen collapses to one surface:

```
┌─────────────────────────────┐
│  Jetzy                  ⚙   │   ← target: silently receivable (needs item 16)
│                             │
│   ┌───────────────────┐     │
│   │   + Add files      │    │   ← unified multi-select (§6)
│   └───────────────────┘     │
│   3 photos · 1 PDF  (12 MB) │   ← staging tray
│                             │
│   ── Nearby ──              │
│   ◉ Maya's iPhone           │   ← passive discovery, caps already known
│   ◉ Office-iMac             │
│                             │
│   [ Show QR ]   [ Scan QR ] │   ← always present, never gated
└─────────────────────────────┘
```

No operation choice. No platform choice. `currentOperation` / `currentPeerPlatform` are removed from the gating path and kept only as derived state for UI that reads `isSender`.

### 2.2 Tap-count vs AirDrop / Quick Share

**Phone → phone, in person (QR):**

| | Jetzy (target) | AirDrop | Quick Share |
|---|---|---|---|
| Pick files | Add files → select | (already in-app share sheet) | Share → Quick Share |
| Identify peer | Scan QR / tap auto-appeared chip | tap recipient avatar | tap recipient |
| Confirm | auto (paired) / 1 tap (stranger) | receiver taps Accept | receiver taps Accept |
| **Sender taps** | **2** | **2** | **2** |
| **Receiver taps** | **1** (0 paired†) | **1** | **1** |

† The **0-tap** paired path is *target-with-dependency*, not current: it requires persisted pairing that does **not** exist yet (promote the in-session `handshakeTiebreaker` to a stored device key, §2.4). Until that ships, every receive is 1 tap (matching AirDrop/Quick Share, not beating them). The 1-tap figure is the buildable-today number.

Jetzy matches sender taps today and **beats** both on receiver taps for paired peers *once pairing persistence ships* (auto-accept, §2.4 — see the † caveat). And it offers a universal **QR / app-less browser** fallback that AirDrop and Quick Share structurally cannot.

### 2.3 The bidirectional protocol sketch

**Owner's question — "does it work both ways; can both people send?" Answer today: NO.** When both peers stage files, the both-offering case picks **one** sender via the antisymmetric `key()` tiebreak (`DirectionResolver.kt:44`); the loser is forced to `RECEIVE` and **its staged files are silently dropped** — `TransferDirection` is `SEND/RECEIVE/NONE` only, with no `BIDIRECTIONAL`. That silent drop is a verified data-loss bug (B47, §5). **Proposed answer: YES, via sequential bidirectional** — both sets move over the one connection, nobody picks a role. **Recommendation: ship sequential bidirectional first, defer full-duplex.**

On every transport here the bottleneck is the single shared radio/link, so full-duplex does *not* increase aggregate throughput — it only interleaves, and on half-duplex links (BT SPP, single-GO Wi-Fi Direct) it can be *worse*. The user-visible goal ("both people picked files, both sets move, nobody chose a role") is fully met by sequential bidi at a fraction of the risk.

**Sequential bidirectional (protocol v4):**

```
DirectionResolver.resolve(localOffering, remoteOffering):
    both offering  -> BOTH(firstSender = key-winner)   // existing antisymmetric key, zero round-trips

beginTransfer():
    when BOTH:
        if I am firstSender:  sendPhase();   receivePhase()   // same duplex channel
        else:                 receivePhase(); sendPhase()

phase boundary:
    phase-1 sender sends its files, then DoneFrame; waits for phase-1 receiver's DONE ack.
    ONLY after that DONE round-trips does phase-2 begin: the phase-2 sender writes its
    own ManifestFrame. No separate ROLE_SWAP verb — the ManifestFrame IS the swap marker.
    (The receive loop already exits on DONE and is ready to read a fresh manifest.)

ledger keyed by (direction, fileIndex)  // two pure ResumePlanner instances, persisted by sessionId
transferComplete = true  // only after BOTH phases write/read DONE
```

**Wire format for the swap:** *no new verb.* The existing `DoneFrame` closes phase 1 and its DONE ack is the explicit "phase-1 fully drained" signal; the phase-2 sender's existing `ManifestFrame` opens phase 2. This reuses frames already defined in `JetzyProtocol.kt` — zero new opcodes.

**Resume across a mid-bidi reconnect.** Each direction owns its own `ResumePlanner` ledger keyed by `(sessionId, direction)`, both persisted (echo the sender sessionId per B37). On reconnect the two sides first compare a one-byte **phase cursor** (phase-1-incomplete vs phase-1-done):
- Drop *during phase 1* → resume phase 1 from its ledger offset; phase 2 has not started, nothing to redo.
- Drop *during phase 2* → phase 1's DONE already round-tripped, so phase 1 is **not** re-sent; resume only phase-2's ledger from its last offset. The key-winner does **not** re-derive direction — `BOTH(firstSender=key)` is recomputed identically from the unchanged antisymmetric key, so both sides agree on which phase they're in without a negotiation round-trip.

This makes resume the *equal* of the unidirectional path rather than its weak leg: the only new state is one phase cursor on top of the two existing ledgers.

**Full-duplex** (interleaved `[dir:1][fileIndex:2][len:4][bytes]` framing + per-direction CRC + a writer mutex) is a 2–3 week protocol rewrite with marginal upside on shared-bottleneck radios — **defer, likely indefinitely.**

### 2.4 Consent & auto-save

**Consent without a new gate.** Tiered policy that never costs the saved tap:

1. **Paired devices → auto-accept (0 taps).** Persist a stable per-peer key (promote the existing `handshakeTiebreaker` to a stored device key). Known peers skip consent.
2. **Unknown device → one-tap, non-modal banner** ("Maya wants to send 3 photos · Accept / Decline"). Insert an `await` between `readManifest()` (`P2PManager.kt:579`) and `writeManifestAck()` (`P2PManager.kt:625`); on decline send `ManifestAckFrame(REJECTED)`. The sender **already handles `REJECTED`** (`P2PManager.kt:438-443`) — **no protocol change needed.**

**Auto-save (fixes the live data-loss bug).** Received files stage to `SystemTemporaryDirectory` and `purgeUnsavedReceivedFiles()` (`P2PManager.kt:320-332`) deletes them unless `saveComplete.value` is true; `cleanup()` calls purge on every teardown (`P2PManager.kt:280`). So **tapping "Done" before "Save" silently destroys the just-received files.** Fix: when `transferComplete` flips on the receive path, immediately call the existing, zip-slip-safe `finalizeReceivedFilesAt(defaultSaveDir)` (`P2PManager.kt:954`) instead of waiting for a manual tap. Bytes land in a real folder the instant CRC passes; the "Save to…" affordance becomes a *move*, never a gate. Per-platform `defaultSaveDir` is a small spike (Android MediaStore vs app-scoped; iOS Files-visibility needs `UIFileSharingEnabled`/`LSSupportsOpeningDocumentsInPlace`), not a one-liner.

---

## 3. The handshaking matrix

Two findings reframe everything below and were **not** in the original audit:

1. **macOS reports `Platform.PC`** (`PlatformUtils.macos.kt:21`); the `Platform` enum is only `Android/IOS/Web/PC`. The negotiator **cannot tell macOS from Windows from Linux.** Fine for LAN/mDNS; wrong for any per-OS transport choice. Fix before solving the no-LAN desktop cells.
2. **The live path never consumes pre-socket capabilities.** `qrData.capabilities` is produced (Android) and parsed but read by no scanner; the mDNS TXT record is **empty**; every discovered `P2pPeer` ships a hardcoded `signalStrength=3`. The negotiator only runs *after* a socket exists, on the HELLO frame.

**Legend:** ✅ works today · 🟡 cheap wiring (managers exist, glue needed) · 🟥 needs a hardware/native spike · ⛔ physically impossible · 💀 *unreachable in current code* (manager/engine exists but no call site — looks cheaper than it is until wired).
**Conditions:** SAME = shared LAN · DIFF = both online, different nets · GREEN = radios present, no common net · NOWIFI = no Wi-Fi at all.
**Why DIFF often collapses into GREEN/NOWIFI below:** Jetzy ships only *local* transports, so "both online on different networks" buys nothing today — there is no relay that consumes internet reachability, so a DIFF pair falls back to the same self-made-segment radios as GREEN/NOWIFI. DIFF becomes a *distinct, better* answer only once a relay exists (WebRTC, HACK 12 / item 19): then a DIFF pair has a routable path that a true NOWIFI pair does not. Cells are split below wherever DIFF already differs today.

### Table A — Phone ↔ Phone

DIFF = GREEN = NOWIFI on every cell here today (no relay exists; see conditions note). The split would appear only with HACK 12.

| Pair / Cond | Rendezvous | Data transport · host | Status |
|---|---|---|---|
| Android↔Android **SAME** | mDNS auto-discovery (no QR) | mDNS-TCP · tiebreak | ✅ |
| Android↔Android **DIFF** | Wi-Fi Aware NAN (own cluster); no relay used | Wi-Fi Aware · either | ✅ NAN chips; else 🟡 hotspot (same as GREEN) |
| Android↔Android **GREEN/NOWIFI** | Wi-Fi Aware NAN (own cluster, no AP) | Wi-Fi Aware · either | ✅ where chip supports NAN; else 🟡 hotspot |
| iPhone↔iPhone **SAME** | mDNS or Multipeer browse | MPC / mDNS-TCP · either | ✅ |
| iPhone↔iPhone **DIFF/GREEN/NOWIFI** | **MultipeerConnectivity** (own Wi-Fi+BT, no router) | **MPC · either** | ✅ — *the clean answer for two iPhones; identical with or without infra* |
| Android↔iPhone **SAME** | mDNS auto-discovery | mDNS-TCP · tiebreak | ✅ |
| Android↔iPhone **DIFF** | **Android shows QR for its SoftAP; iPhone scans + joins** (internet unused) | Hotspot-LAN · **Android hosts** | 🟡 (today's default iOS path; needs SoftAP spike) |
| Android↔iPhone **GREEN** | Android shows QR for its SoftAP; iPhone scans + joins | Hotspot-LAN · **Android hosts** | 🟡 (needs SoftAP spike) |
| Android↔iPhone **NOWIFI** | Android SoftAP (works on cellular); iPhone joins | Hotspot-LAN · Android hosts | 🟡 Android; no BLE fallback (🟥) |

### Table B — Phone ↔ Desktop (desktop = mac/Win/Linux, all `Platform.PC`)

Desktop = mac/Win/Linux, all reported as `Platform.PC` (B-finding §3): the negotiator *cannot* tell them apart, so the per-OS split below is what a *correct* negotiator would do, not what today's code distinguishes. Phone shows/scans direction: **desktop SHOWS QR, phone SCANS** for every QR rung (§3.2; desktop has no webcam path, `QRDiscoveryScreen.desktop.kt:225`).

| Pair / Cond | Rendezvous (who shows/scans) | Data · host | Status |
|---|---|---|---|
| Phone↔Desktop **SAME** | mDNS auto-discovery (no QR) | mDNS-TCP · tiebreak | ✅ |
| Phone↔Desktop **DIFF** | Android SoftAP; **desktop manual Wi-Fi join** (no auto-join API); internet unused | Hotspot/mDNS once joined | 🟡 (clunky manual join; same as GREEN today) |
| Phone↔Desktop **GREEN · Linux** | Android↔Linux Wi-Fi Direct | Wi-Fi Direct | 🟡 (And↔Linux; iPhone↔Linux 🟥) |
| Phone↔Desktop **GREEN · Win** | Android SoftAP + desktop manual join | Hotspot-LAN · Android host | 🟡 Android↔Win; 🟥 iPhone↔Win |
| Phone↔Desktop **GREEN · mac** | Android SoftAP + desktop manual join | Hotspot-LAN · Android host | 🟡 Android↔mac; 🟥 iPhone↔mac |
| Phone↔Desktop **NOWIFI** | Android SoftAP + desktop joins | Hotspot-LAN · Android host | 🟡 Android; 🟥 iPhone↔Win/mac |

### Table C — Desktop ↔ Desktop

| Pair / Cond | Rendezvous | Data · host | Status |
|---|---|---|---|
| Desktop↔Desktop **SAME** | **mDNS auto-discovery** (no camera needed) | mDNS-TCP · tiebreak | ✅ |
| Desktop↔Desktop **DIFF** | mDNS won't cross subnets; paste-QR has no L3 route | — | 🟥 (genuinely hard; "get on one LAN") |
| Desktop↔Desktop **GREEN** | Linux↔Linux Wi-Fi Direct (no data path yet); mac/Win cannot self-bootstrap | ⛔ mac/Win; 🟥 Linux | 🟥 |
| Desktop↔Desktop **NOWIFI** | Only BT-SPP, Linux↔Linux | BT-SPP · either (Linux) | ⛔ for mac/Win pairs |

### Table D — Any device ↔ Web browser (no app)

| Pair / Cond | Rendezvous | Data · host | Status |
|---|---|---|---|
| **Phone**-host → Browser **SAME** | phone shows `http://<ip>:<port>/`; `WebReceiver.classify` peeks `GET ` | HTTP on phone's TCP socket · phone hosts | 💀 engine done, **accept-loop has zero call sites** (B12) |
| **Desktop**-host → Browser **SAME** | desktop shows URL/QR; same `WebReceiver` engine | HTTP on desktop's TCP socket · desktop hosts | 💀 same engine, same missing accept-loop |
| App-host → Browser **DIFF** | browser needs a routable IP to the host; internet alone doesn't route to a LAN-private host | — | ⛔ today (a relay/tunnel would change this — not built) |
| App-host → Browser **GREEN/NOWIFI** | browser must join the host's SoftAP first, then hit the LAN IP | HTTP on host · app hosts | 💀 (engine unwired) *and* requires SoftAP join |
| Browser → App (browser sends) | browsers can't open raw TCP | — | 🟥 out of scope (serverless, no signaling) |

### 3.1 The auto-bootstrap decision tree (first match wins)

This is what `proceedFromMainScreen` *should* call instead of `getSuitableP2pManager(peerPlatform)`. Direction is implied (you staged files → offering); platform is never asked (it arrives in caps/HELLO).

1. **Both on a shared LAN?** → mDNS; negotiate over the surfaced peer. *Zero taps after "pick files."* — **today.**
2. **iOS↔iOS?** → MultipeerConnectivity; on/off LAN. — **today.**
3. **Both Wi-Fi-Aware capable?** → NAN cluster, no infra. — Android ✅; iOS26 🟡.
4. **One side can host SoftAP (Android)?** → Android shows QR; peer joins+dials. — **today** (the iOS default).
5. **Android + Linux, greenfield?** → Wi-Fi Direct. — Android ✅; desktop data path 🟥.
6. **Same LAN but mDNS blocked / app-less peer?** → show URL/QR; phone scans, desktop pastes, browser opens (`WebReceiver`). — paste ✅; web accept-loop 🟡.
7. **Nothing else?** → BT-SPP (Android/Linux) ✅; else honest "get on the same network."

### 3.2 Explicit answers to the owner's questions

- **Can phones BROADCAST (not just QR)?** Yes — but today only **Wi-Fi Aware (Android↔Android)** and **mDNS (any pair on the same LAN)** actually broadcast. The universal cross-platform, no-LAN beacon would be **BLE advertising**, which **does not exist in the code** (`Bluetooth` bit reserved, `isLocallyCapable=false`, `P2pTechnology.kt:313`). That is the single biggest missing radio. **Reconciling the §2.1 mockup:** "silently receivable to a stranger with no shared LAN" works on **no device-pair today** — Table A grades every no-LAN cross-platform cell as 🟥/🟡, and persistent passive advertise is roadmap item 16. The mockup caption is marked *target* accordingly.
- **Desktop shows QR vs scans?** **Desktop SHOWS, phone SCANS.** The phone camera is reliable; the desktop has **no live webcam path** — the JVM client panel literally says *"Desktops have no camera path here. Paste the QR text or load a saved QR image."* (`QRDiscoveryScreen.desktop.kt:225`). Never ask a desktop to scan.
- **Desktop ↔ desktop (no camera either side)?** QR-scan is impossible. Ladder: (1) **mDNS** on the same LAN — covers ~90% of desktop pairs, no QR at all; (2) **paste-text / load QR image**, ideally upgraded to a 3-word pairing code; (3) Linux↔Linux Wi-Fi Direct/BT-SPP (🟥); (4) mac/Win with no LAN = **genuinely unsolvable peer-to-peer** without a cable — say so honestly.
- **No LAN/Wi-Fi at all — is mDNS an option?** **No.** mDNS needs a shared L2 multicast segment; off-LAN it is dead. The transports that *manufacture their own segment* are the answer: Wi-Fi Aware, Wi-Fi Direct, Android SoftAP, MPC (iOS↔iOS), BT-SPP. **This is exactly why the Happy-Eyeballs race must exist** — a high-ranked-but-unavailable mDNS should cost one stagger step, not a dead end.

### 3.3 Unreachable cells (flagged today)

| Cell | Why | Fix |
|---|---|---|
| Anything over **BLE** | no BLE manager | §10 hardware tier |
| Anything over **NearbyConnections** | no manager (`isLocallyCapable=false`) | low priority (Aware/Direct cover Android↔Android) |
| **Web-browser receive** | `WebReceiver.serve` has zero call sites | wire accept-loop peek+route |
| **Desktop Wi-Fi Direct** | omitted from factory, no data path (`Main.kt:89`) | finish or delete |
| **macOS-native P2P** | macOS=`Platform.PC`, no macOS callback, QR screen is an error stub | ship desktop-mac as JVM only, or wire a callback |
| **Negotiator-driven connect** | `getManagerForTechnology` never called | wire the race — **the keystone** |

---

## 4. New matrix hacks

Fresh moves beyond the audit's six, ordered by *(magic ÷ cost)*.

### HACK 7 — Three-word pairing codes (highest value, lowest cost)
**What.** Replace the desktop "paste a 200-char blob" flow with a 3-word code (`tiger-velvet-comet`). On-LAN, the words salt the mDNS instance name (`jetzy-tiger-velvet-comet`); the receiver browses for exactly that name and dials the resolved IP:port. **Feasibility:** 100% today for the LAN variant — `NsdManager`/JmDNS/`NSNetService` all key on instance name; 33 bits of entropy is ample for a short-lived local rendezvous. **Risk: LOW.** Only failure mode is multicast-blocked Wi-Fi (same as today). New pure `PairingWords.kt`; reuses the existing TCP server + HELLO unchanged. *Caveat:* iOS browse triggers the Local Network permission prompt on first run.

### HACK 8 — QR/link as a capability beacon, negotiate at decode time (finishes audit HACK 1 properly)
**What.** Make the scanner consume `qrData.capabilities` and a new `QRData.platform` field, build the remote `CapabilityProfile`, run `TransportNegotiator.best()` *before dialing*, and instantiate via `getManagerForTechnology`. Also fix the desktop QR, which omits `capabilities` entirely (`LanHostP2PM.desktop.kt:76` → defaults `0L`). **Feasibility:** the caps field already round-trips (`QRData.kt:59`); the negotiator and factory exist. **Risk: LOW.** *Caveat:* `toQRData()` uses `split(":", limit = 7)` and is already at 7 fields — adding `platform` requires bumping the split limit or inserting before caps, not a free append.

### HACK 9 — Capability-bearing mDNS TXT → zero-QR, zero-click same-LAN pairing
**What.** Put caps + platform + pairing-salt into the mDNS TXT record (today empty) so discovered peers carry full profiles and a *real* `P2pPeer` (retiring the `signalStrength=3` lie). The negotiator then runs at discovery time; tapping a peer connects over the pre-negotiated best transport with no QR. **Feasibility:** `NsdServiceInfo.setAttribute`, JmDNS props map, iOS TXT all support it (~20 bytes vs a ~1300-byte budget). **Risk: LOW-MEDIUM** and the most overstated item in the brief — **the catch is TXT *readback***, not writing: the in-use deprecated `resolveService` path returns flaky/empty `getAttributes()` pre-API-33 (robust path is API-34 `registerServiceInfoCallback`), and iOS needs the `didUpdateTXTRecordData:` delegate, not the current `didResolveAddress` one. Treat as a per-version device spike, not pure wiring.

### HACK 10 — Universal deep-link ("send the link over any messenger")
**What.** Encode the beacon as `https://jetzy.app/r#<payload>` (fragment → serverless). App installed → opens into the connect flow; no app → thin static launcher page. **Feasibility:** standard App Links / Universal Links. **Risk: MEDIUM** — needs a domain + static asset (the first deliberate bend of "zero servers," but a dumb HTML file). *Correction to the original pitch:* a browser page **cannot** reliably probe `http://192.168.x.y:port` from an HTTPS origin — mixed-content + Private Network Access (Chrome preflight, which `WebReceiver` doesn't answer) will block the "detect the LAN host" auto-redirect. Drop that sub-feature.

### HACK 11 — NFC tap-to-pair (Android↔Android)
**What.** Beam the beacon over NDEF on tap; Wi-Fi carries the bytes. **Risk: MEDIUM, Android-only** (iOS NFC is read-only/gated). **Correction:** Android Beam (`setNdefPushMessage`) was **removed in API 34** — re-spec as **HCE** (`HostApduService`), not Beam. Hardware spike.

### HACK 12 — Account-less stateless WebRTC relay for cross-network
**What.** When no mutual local transport exists, meet in a stateless rendezvous (keyed by HACK 7 words / HACK 10 link), exchange SDP, and send files peer-to-peer via ICE (TURN only as last resort). **The bytes never touch a server; only ~hundreds of setup bytes transit a stateless broker.** This is the only path that survives *no shared network* — the thing AirDrop/Quick Share cannot do. **Risk: HIGH.** *Corrections:* `RTCDataChannel` in **WKWebView is unavailable** (use native `WebRTC.framework` on iOS); JVM needs a per-arch libwebrtc binding; data channels are **message-oriented (~256 KB SCTP limit)**, so a chunking adapter into `ByteWriteChannel` is required — not "zero changes." Still the best magic-per-risk moonshot because `RTCDataChannel` ≈ a duplex byte stream that drops into `startTransferWithChannels` (`P2PManager.kt:104`).

### HACK 13 — Ultrasonic handshake (novelty)
**What.** Encode the 33-bit salt as an 18–20 kHz chirp; the mic decodes it. **Risk: HIGH** — capture APIs exist everywhere but DSP reliability is environment-dependent; iOS adds a mic-permission prompt. Ship as an optional novelty, never a primary path.

**Rejected (honesty matters):** animated/cyclic QR (the ~80-byte payload is nowhere near static-QR capacity — pure added failure mode); clipboard-rendezvous (subsumed by HACK 10); browser-receive-then-re-share chains (a browser tab can't host an inbound socket).

### Honest re-examination of the existing six

| Hack | Verdict on re-audit |
|---|---|
| **1 — zero-handshake QR negotiation** | **Overstated / not actually wired.** The scanner still drops `qrData.capabilities`; the desktop QR doesn't even carry them; there's no `platform` field. Negotiation happens late, in HELLO, after the transport was already platform-guessed. HACK 8 is the real fix. |
| **2 — telepathic roles** | **Works in HELLO, impossible on discovery.** The mDNS advert is empty, so an auto-discovered peer is a bare name + fake `signalStrength=3`; roles can't be pre-computed before the socket. HACK 9 fixes the advert side. |
| **3 — Happy-Eyeballs racing** | **Schedule sound; executor's hard parts under-specified.** (a) `disruptsLocalConnectivity` is a hand-coded `when` (`P2pTechnology.kt:101`) that flags only Hotspot+WiFiDirect — but **Wi-Fi Aware is disruptive on many chips** that can't run NAN concurrently with STA/SoftAP; this needs a *runtime* concurrency probe (`isStaConcurrencyForLocalOnlyConnectionsSupported()`). (b) The `transferBegun` guard is **per-manager, not per-race** (`P2PManager.kt:203`) — two raced managers could each pass it and both start; the executor needs a **race-level latch**. |
| **4 — gear-shift upgrade** | **Crown jewel with two real seams.** (a) On iOS, bringing up Wi-Fi Aware/`NEHotspotConfiguration` likely **drops the LAN association** the cheap link rode on — design a **hard cutover** (drain → checkpoint → drop → reattach → resume), not a graceful overlap. (b) The ledger flushes on *file completion*, not on demand — a mid-file swap needs an **explicit checkpoint-flush** in the UPGRADE frame or the new link resumes from a stale offset. This is the protocol detail the audit omits. |
| **5 — app-less web receive** | **Engine solid, unwired, plus a security gap.** No accept loop peeks/routes (zero call sites). And `serve()` serves `/dl/<idx>` with **no auth** (`WebReceiver.kt:158`) — every device on the LAN/hotspot can enumerate and pull the sender's staged files. Add a path token from the QR. Header reader is also unbounded (slowloris) — cap line length + count when wiring. |
| **6 — radio striping** | **Correctly a moonshot.** Blocked by the strictly-sequential whole-file framing *and* the strict-equality VERSION gate (`JetzyProtocol.kt:124`) — needs range-request frames + real min/max version negotiation first. Research spike. |

---

## 5. Bugs & correctness (verified only)

Only **confirmed** or **partial** findings appear here, with corrected severities. Refuted claims are footnoted.[^refuted]

**Status of each finding (so a latent/partial isn't read as a live crash):** all rows are verified against `master` through `ba6dbf5` and are *live* unless the Bug cell says otherwise. The exceptions, called out inline: **B8** and **B21** are **Latent** (the buggy code exists but its trigger/consumer isn't reachable yet — they become live only after a future change); **B20** is a **Partial** (the unbounded-count and space-gate-bypass legs are live, the instant-OOM leg is not). Severity reflects *current* impact, so a latent finding sits below live ones of the same shape. Everything else is a present-tense bug.

| # | Sev | Bug | File:line | Fix |
|---|---|---|---|---|
| B1 | **High** | **Receiver auto-saves nothing — tapping "Done" before "Save" purges received files.** `cleanup()`→`purgeUnsavedReceivedFiles()` deletes `itemsRECEIVED` unless `saveComplete` is true. | `P2PManager.kt:280,320-332` | Auto-call `finalizeReceivedFilesAt(defaultSaveDir)` on `transferComplete`. *Highest-value fix.* |
| B47 | **High** | **Both-offering case silently drops the loser's files (data loss).** When both peers stage files, the antisymmetric `key()` tiebreak forces one side to `RECEIVE`; there is no `BIDIRECTIONAL` direction, so the loser's staged files never transmit and the user gets no error. | `DirectionResolver.kt:44` | Add `DirectionResolver.BOTH` + sequential bidi (§2.3), or at minimum surface "your files weren't sent." |
| B2 | **High** | **No end-to-end encryption on any transport.** Raw bytes + a CRC32 (not a MAC) over TCP/streams; on shared/public Wi-Fi any LAN device sniffs filenames + contents. Even the one "encrypted" link (Wi-Fi Aware) uses a hardcoded public PSK `"jetzy-nan-psk"`. | `P2PManager.kt:494,499`; `WifiAwareP2PM.kt:490` | X25519 ECDH after HELLO → ChaCha20-Poly1305 AEAD over all frames; SAS/QR-bound auth. |
| B3 | **High** | **No authentication / pairing / receiver consent.** First socket to connect drives a full transfer; the receiver auto-acks the manifest and stages files before any user interaction; iOS MPC auto-accepts every invitation. | `HotspotP2PM.kt:125`; `P2PManager.kt:247,625`; `MpcP2PM.kt:120-124` | Session-token HMAC challenge; consent `await` before `writeManifestAck` (reuses `REJECTED`). |
| B4 | **High** | **QR carries WPA2 SSID + passphrase in plaintext;** on-screen "mask" shows the first 8 chars. Photograph the QR → join the network → race the unauthenticated listener. | `QRData.kt:33-47`; `QRDiscoveryScreen.android.kt:354` | Don't rely on QR secrecy; pair with B2/B3 auth; rotate per attempt; stop rendering recoverable creds. |
| B5 | **High** | **Sender file shrink between manifest and read hangs the receiver.** Sender breaks on `read==-1`, writes a CRC anyway; receiver blocks in `readFully` for bytes that never arrive (CRC misread as payload) until the 8s watchdog. | `P2PManager.kt:494-527,696` | Abort the file with a protocol error on short read, or send an actual-length prefix. |
| B6 | **High** | **iOS QR scan failure freezes the camera; snackbar points to a "Retry" that doesn't exist.** `stopRunning()` before detection kills the preview; a plain TCP/Wi-Fi-join timeout never sets `joinRaceDetected`, so no Retry dialog renders — only Cancel. | `QRScannerController.kt:105`; `LanWifiP2PM.kt:89-112`; `QRDiscoveryScreen.ios.kt:171-179` | Restart capture on failure; broaden Retry to any failure, not just the auto-join race. |
| B7 | **High** | **Phantom transport: desktop advertises BluetoothSpp it can't use** (macOS/Win return failure; Linux needs sudo); same shape for HotspotLAN on PC (can't host). | `P2pTechnology.kt:295,267`; `BluetoothSppP2PM.desktop.kt:177` | Gate `isLocallyCapable` on runtime ability; registry invariant test: every advertised bit resolves to a non-null manager. |
| B8 | **Latent** | **`BuildConfig.DEBUG` hardcoded `true`** for all build types — any DEBUG-gated path *would* ship in release. *(Latent: the only current consumer is a no-op `repeat(10){}` with a commented body, so nothing leaks today; severity rises to High the moment a real DEBUG-gated branch lands.)* | `shared/build.gradle.kts:189` | Derive `DEBUG` from build type. |
| B9 | **High** | **Android ships unused storage permissions + `requestLegacyExternalStorage`** (no Kotlin usage; FileKit/scoped storage is used) — Play sensitive-permission-review bait. | `shared/src/androidMain/AndroidManifest.xml:18-19`; `androidApp/.../AndroidManifest.xml:9` | Remove `READ/WRITE_EXTERNAL_STORAGE` + legacy flag; verify on a clean build. |
| B10 | **High** | **RECEIVE with zero files on both sides dead-ends in `NONE`** only after a full connect+handshake (4–5 screens in), then aborts. | `P2PManager.kt:251-254`; `MainScreen.kt:91` | Make "who has files" obvious pre-connect; fail fast and keep discovery alive so adding a file proceeds without re-pairing. |
| B11 | **Med** | **Desktop `WiFiDirectP2PM` is unreachable AND non-functional** — never constructed by the callback, and `connectToPeer` returns success without ever setting `connection`. 344 dead lines. | `WiFiDirectP2PM.desktop.kt:225,243,283`; `Main.kt:89` | Delete (and the WiFiDirect PC `supportedPlatforms` entry), or finish the data path. |
| B12 | **Med** | **WebReceiver app-less engine is unreachable** — every accept site sets `connection` directly and runs the native handshake; a browser `GET ` fails the magic gate. Peek-replay seam for the JETZY path doesn't exist either. | `WebReceiver.kt:137`; `LanMdnsP2PM.kt:91` | Peek 4 bytes at each accept site → `classify` → branch; add a prefix-replaying channel for the native path. |
| B13 | **Med** | **Strict version-equality gate makes every protocol bump a flag-day** — a v3 peer can't talk to v4 even for additive changes; no forced-update channel. | `JetzyProtocol.kt:124` | Min-supported-version semantics; negotiate effective version down to `min(local, peer)`. |
| B14 | **Med** | **CRC mismatch mid-batch aborts the whole transfer** (both sides throw) instead of retrying the one corrupt file. | `P2PManager.kt:541,743` | Retry the single file in-band up to N times before aborting. |
| B15 | **Med** | **Two `SelectorManager(PreferablyIO)` leak** — created inline, never closed in `cleanup()` (NIO selector + thread per discovery/fallback). | `WiFiDirectP2PM.desktop.kt:93`; `LanHostP2PM.desktop.kt:56` | Hoist to class-scoped fields, close in `cleanup()` (as `LanMdnsP2PM.desktop.kt` already does). |
| B16 | **Med** | **Foreground service + 30-min wakelock + KEEP_SCREEN_ON start at *discovery* time,** before any transfer; notification falsely says "transferring." | `JetzyViewmodel.kt:207`; `JetzyForegroundService.kt:73` | Start FGS/wakelock only when a transfer begins; fix notification text. |
| B17 | **Med** | **Device identity uses `Build.MODEL`** — two same-model phones (two Pixels) collide in MPC/peer-list dedup keyed on display name. | `PlatformUtils.android.kt:22`; `MpcP2PM.kt:101-103` | Append a stable per-install suffix; key peer maps on a transport id, not name. |
| B18 | **Med** | **Android↔Android can't bootstrap over QR/hotspot** — Android only ever *shows* a QR, never scans; the fallback ladder has no QR rung for Android peers. | `MainActivity.kt:84-118`; `QRDiscoveryScreen.android.kt:82` | Give Android a QR-scan path (it has a camera). |
| B19 | **Med** | **No incoming-transfer consent** (see B3) + **TEXT entries auto-decoded unbounded** — a multi-GB file declared `entryType=TEXT` is read whole into memory and rendered. | `P2PManager.kt:625,746-752` | Consent gate; only auto-decode TEXT under a small cap (e.g. 64 KB). |
| B20 | **Med** | **Hostile manifest DoS** — no bound on `entryCount`, no `sizeBytes>=0` check, `totalBytes` unvalidated vs entry sum (bypasses the space gate). *(Partial: eager `.map` stalls on `readString` rather than instant-OOM, but the unbounded count + space-gate bypass are real.)* | `JetzyProtocol.kt:168-175` | Bound `entryCount`/`totalBytes`; reject negatives; verify `sum(sizeBytes)==totalBytes`. |
| B21 | **Med** | **`upgradeTarget` ignores `disruptsLocalConnectivity`** — would recommend WiFiDirect (q84, disruptive) over mDNS (q80), tearing down the LAN the session rides on. Latent (upgrade not yet executed). | `TransportNegotiator.kt:98-101` | Require a non-disruptive (or meaningfully-better) target when current is non-disruptive. |
| B22 | **Med** | **Handshake overlay has no timeout/cancel** — full-screen modal swallows input during `establishTcpClient`/`connectToPeer`; only escape is system-back (none on desktop). | `QRDiscoveryScreen.kt:41-50`; `PeerDiscoveryScreen.kt:281-292` | Add Cancel + timeout that resets `isHandshaking` and shows "Couldn't connect." |
| B23 | **Med** | **Permission-gate auto-advances on a single transient grant snapshot** (one 400ms poll all-true → auto-`onConfirm`); `structuralKey` keyed only on ids. | `PermissionGateDialog.kt:79-103` | Require all-granted for ≥2 consecutive polls. |
| B24 | **Med** | **Foreground service type always `dataSync`** even for Bluetooth transfers (`connectedDevice` fits; `BLUETOOTH_CONNECT` is already held). Android 14 caps `dataSync` ~6h/day. | `JetzyForegroundService.kt:52` | Pass per-transport FGS type. |
| B25 | **Med** | **iOS camera-permission-denied is swallowed** — black preview, no error, no Settings deep-link. | `QRScannerController.kt:67` | Check `authorizationStatus`; surface a "Camera access needed" state + Settings link. |
| B26 | **Med** | **iOS QR scanner accepts ANY scanned QR** (no magic-prefix/format check), then dials garbage (port defaults to 80) → frozen camera + 10s timeout. Desktop *does* validate. | `QRScannerController.kt:102-111`; `QRData.kt:56` | Validate Jetzy format before dialing (mirror desktop). |
| B27 | **Med** | **iOS Info.plist requires `armv7`** — a 32-bit capability on an arm64-only build (`iosArm64`). | `iosApp/iosApp/Info.plist:67` | Change to `arm64` or remove the array. |
| B28 | **Low** | `switchToNextFallbackTransport` wraps **modulo** the ladder — cycles `1→2→3→1…` forever with no "exhausted" terminal state. | `JetzyViewmodel.kt:155` | Clamp, not modulo; show a terminal message on last-rung failure. |
| B29 | **Low** | **Stall watchdog only fires after the first byte** (`current > 0L`) and only starts after the manifest ack — a hung handshake/manifest never times out. | `P2PManager.kt:894,913,821-822` | `withTimeout` around handshake + manifest; drop the `>0` guard. |
| B30 | **Low** | **Discovered-peer subtitle hardcoded "Wi-Fi Direct"** regardless of actual transport (mDNS/MPC/BT/Aware). | `PeerDiscoveryScreen.kt:506-510` | Render `manager.technology?.displayName`. |
| B31 | **Low** | **SignalBars fabricated** — every peer ships `signalStrength=3`, so always 3/4 bars; mDNS has no RSSI. | `PeerDiscoveryScreen.kt:513`; `LanMdnsP2PM.kt:183` | Remove bars for RSSI-less transports; show a "nearby" chip. |
| B32 | **Low** | **Self-dial guard is name-based** — two devices both named "iPhone" filter each other out as "self"; Android registration race; `P2pPeer.id` = service name (unstable on NSD rename). | `appleMain/LanMdnsP2PM.kt:66`; `LanMdnsP2PM.kt:147` | Advertise + filter on a per-process UUID; use it as `P2pPeer.id`. |
| B33 | **Low** | **mDNS `connection` setter overwrites without closing** on simultaneous inbound+outbound → leaked socket per raced session. | `P2PManager.kt:89-94`; `LanMdnsP2PM.kt:91` | Deterministic tiebreak (lower UUID dials); close the loser socket. |
| B34 | **Low** | **Tiebreak key collides for symmetric hosts** — two identical-model same-OS phones → both pick HOST (`>=` reflexive); no recovery (no race executor). | `TransportNegotiator.kt:109-113` | Thread the per-session `handshakeTiebreaker` nonce into the key. |
| B35 | **Low** | **iOS `getManagerForTechnology(HotspotLAN)` ignores `role`** — would return a client manager on a HOST request (latent; desktop honors role). | `iosMain/main.kt:85-92` | Honor/guard role; test all (technology, role) pairs. |
| B36 | **Low** | **iOS WiFiAware capability vs bridge can disagree** — advertises the bit on iOS 26+ regardless of whether `wifiAwareBridge` is wired; factory returns null and strands the attempt. | `iosMain/main.kt:90`; `P2pTechnology.kt:139` | Require bridge presence in `isLocallyCapable`, or skip-and-continue on null. |
| B37 | **Low** | **Receiver clears whole ledger on session change** without deleting the temp files → orphaned partials; sender session-id instability degrades resume to full re-send. | `P2PManager.kt:617-623` | Delete temp files on reset; persist/echo the sender sessionId. |
| B38 | **Low** | **Progress pins to 1f before `DONE`** — bar shows 100% then the transfer can report failure. | `P2PManager.kt:779,552` | Pin to 1f only after `DONE` round-trips. |
| B39 | **Low** | **Empty folder drives a full "successful" transfer of nothing** (`offeringFiles=true`, 0 files). | `P2PManager.kt:404` | Surface "nothing to send" after flattening. |
| B40 | **Low** | **`finalizeReceivedFilesAt` subfolder collisions overwrite** (no unique-suffix like `allocateTempPath`). | `P2PManager.kt:962-973` | Suffix on collision. |
| B41 | **Low** | **`android:allowBackup=true`** with no exclusions — staged files/prefs extractable via adb backup. | `androidApp/.../AndroidManifest.xml:6` | `allowBackup=false` or exclude temp/received dirs. |
| B42 | **Low** | **MPC `encryptionPreference = MCEncryptionOptional`** — link can negotiate down to plaintext. | `MpcP2PM.kt:45` | `MCEncryptionRequired`. |
| B43 | **Low** | **WiFiDirect `WifiP2pManager.Channel` initialized with null `ChannelListener`** — channel loss never self-heals; retry loop wedges. | `WiFiDirectP2PM.kt:74` | Pass a listener that re-inits on `onChannelDisconnected`. |
| B44 | **Low** | **Stall watchdog cancels the in-memory channel but not bridge pumps** for stream-bridged transports (MPC/Aware/BT) — native read pump keeps running until full cleanup. | `P2PManager.kt:905-917` | Overridable transport-teardown hook from the watchdog. |
| B45 | **Low** | **BT-SPP discovery never re-scans / `accept()` never times out** — scan dies silently after ~12s; server coroutine lives the whole session. | `BluetoothSppP2PM.kt:140,148` | Bounded rescan; cancel on transfer; timeout `accept()`. |
| B46 | **Low** | **Dead protocol verbs** — `AckStatus.REJECTED` / `FileAck.CANCELLED` handled but never sent; `fromCode` maps unknown bytes to them (corruption masquerades as cancel). | `JetzyProtocol.kt:50,59,75` | Wire REJECTED to consent (see B3); throw on unknown codes. |

[^refuted]: **Notable claims checked and dismissed.** *"iOS user picks between two identical 'Another platform' buttons"* — **refuted**: `MainScreen.kt:193-200` hides PC on iOS and labels the self-platform with its real name, so the buttons differ. *"MPC write pump busy-spins"* — **refuted**: it uses Ktor's *suspending* `readAvailable`, which parks rather than hot-loops; only the read-pump channel-close observation stands. *Coroutine leaks on cleanup (old audit CRIT)* — **refuted**: `cleanup()` calls `coroutineSupervisor.cancelChildren()` and subclasses cancel `bridgeJobs`. *Resume-CRC bug (old audit CRIT-1)* — **fixed and regression-tested** (`ResumePlannerTest`). The `priority`/`icon`/`discoveryMode` registry-field claim is **partial** — the per-instance fields are dead, but the `P2pDiscoveryMode` *type* is used elsewhere.

---

## 6. UX / UI friction

The headline: **the UI is one architecture-generation behind its own engine.** Concrete, click-counted:

- **Send/Receive gate is a lie the protocol overrides (priority #2, #1).** `MainScreen.kt:80-95` blocks `Proceed` until `currentOperation != null`, but `beginTransfer` overwrites it from `DirectionResolver` (`P2PManager.kt:244,248`). Pick RECEIVE with files staged → silently flipped to SEND. **Delete the gate**; "select files to send" is the only input. *Saves 1 mandatory tap and removes a trust-breaking contradiction.*
- **Peer-platform gate is forced and load-bearing-but-redundant (priority #2).** `MainScreen.kt:85-89` blocks until `currentPeerPlatform != null`; a wrong guess silently routes to an incompatible transport ("No devices found"). The real platform arrives in `HelloFrame.platform` and `qrData.capabilities`. **Delete the picker**; negotiate from caps. *Saves 1 mandatory tap.*
- **No auto-save + no history (priority #2, #1).** Every receive ends on a mandatory folder-picker; a mis-tap on "Done" destroys the download (B1). *Costs 1 tap + a dialog and risks data loss on every single receive.*
- **Four pickers instead of one sheet (priority #4).** Files/Photos/Videos/Text are separate sub-screens with **three different remove gestures** (`ElementPickingScreen.kt:22-27`; long-press FAB vs per-row X vs expand-delete). Collapse to one OS multi-type picker + one consistent remove gesture + a separate "Add text."
- **No back affordance; logo-as-About button (priority #4).** `navigationIcon` is always the logo (`AdamScreen.kt:118-146`); desktop has no hardware back, so leaf screens can strand. Add a real back arrow; move About to an overflow.
- **Fallback "Try a different transport" is invisible where it's needed.** Gated behind a fixed 6s empty wait and present *only* on `PeerDiscoveryScreen` — the QR cross-platform path (the highest-failure path) has no escape hatch (`JetzyViewmodel.kt:151` only called from `PeerDiscoveryScreen.kt:250`). Fold into the race; surface on every discovery surface.
- **Polish gaps:** fabricated signal bars (B31), mislabeled transports (B30), radar dots collide past 3 peers (`PeerDiscoveryScreen.kt:322`), inconsistent cancel idioms (safe "Done" looks identical to destructive "Cancel transfer", `TransferScreenUI.kt:277-293`), hardcoded English strings + `'file'+'s'` pluralization (`OperationButton.kt:76`, `MainScreen.kt:264-271`), pervasive `contentDescription = null` on primary controls (`OperationButton.kt:68` and many more), dp/sp on the QR screens vs `sdp/ssp` everywhere else.

**Net cold-start today:** ~5–6 deliberate taps (operation + platform + open picker + select + proceed + save). **Target:** 2 (Add files, Scan/peer) for the sender, 0–1 for the receiver.

---

## 7. Networking, protocol & best-practices

- **The negotiator runs, but acts on nothing.** `negotiatePeerCapabilities()` (`P2PManager.kt:837-854`) computes `negotiatedTransports` (zero reads outside the declaration) and `recommendedUpgrade` (feeds one display-only badge, `TransferScreenUI.kt:354`). The connection is already up over a platform-guessed manager (`getSuitableP2pManager`, `JetzyViewmodel.kt:130`). **Wire the race or, minimally, consume `negotiatedTransports[0]` at connect time.**
- **Three copy-pasted `when(peerPlatform)` tables are the real selector** (`MainActivity.kt:84-118`, `Main.kt:50-62`, `main.kt:41-58`), each admitting "we don't have a way to know the peer's OS." Replace with `negotiate()` → `getManagerForTechnology`.
- **`getManagerForTechnology` and `TransportCoordinator.schedule` are fully implemented, called from zero production sites.** The factory and the Happy-Eyeballs schedule exist; the **executor that runs the race does not** — its own KDoc concedes it "lands once managers expose a uniform connect primitive." This is the keystone (§10).
- **Protocol hygiene:** strict version-equality (B13) should become min-version negotiation *before* any security bump (else teams keep old plaintext paths alive). `negotiate()`/`best()` duplicate the role pipeline (`TransportNegotiator.kt:53-85`) — make `best()` = `negotiate().firstOrNull()`. Add a `(quality desc, capabilityBit asc)` tie-key so equal-quality ranking is deterministic (`:66`). Add a registry invariant test: every advertised bit resolves to a non-null manager for some role (would have caught B7, B36).
- **Bidirectional readiness:** the data plane already runs over any duplex `ByteReadChannel`/`ByteWriteChannel`, and `ResumePlanner` is pure — sequential bidi (§2.3) needs only a `DirectionResolver.BOTH` case, the existing DoneFrame→ManifestFrame phase boundary (no new opcode), and a two-key ledger plus a one-byte phase cursor for mid-bidi resume. The architecture was designed for this.

---

## 8. Security & privacy

**Trust model today: "trust whoever connects."** There is no pairing, no authentication, and no receiver consent (B3); the first socket to reach a listener drives a full transfer, and the self-asserted `HelloFrame.name` is never verified.

**Encryption reality: none at the app layer.** All frames — HELLO, manifest (filenames + sizes), payload — are cleartext (B2). On shared/public Wi-Fi, mDNS/LocalNetwork peers are fully sniffable; WPA2 protects against off-network attackers, not other clients on the same AP. CRC32 is integrity-only — an active MITM can alter bytes and forge the CRC (B-low). The one "encrypted" transport (Wi-Fi Aware) uses a hardcoded public PSK shipped in every binary, and MPC is `MCEncryptionOptional` (can negotiate to plaintext). **Recommended:** X25519 ECDH after HELLO → ChaCha20-Poly1305 AEAD over all frames, authenticated by a short SAS code or bound to the QR/session token; the AEAD tag then supersedes the CRC.

**Bootstrap secrets in plaintext:** the QR encodes the live WPA2 SSID + passphrase, and the on-screen "mask" reveals the first 8 chars (B4). The fix is not QR secrecy but app-layer auth so QR possession alone is insufficient.

**Open file server:** `WebReceiver.serve()` (once wired) answers `GET /` with an index of every staged file and `GET /dl/<idx>` with the bytes — **no token, no auth** (B-high in design, low today because unwired). Add a path token from the QR; cap the header reader.

**Two new things to gate:** unbounded TEXT auto-decode (B19) and unbounded manifest entry counts (B20) are cheap remote-DoS / OOM vectors from any unauthenticated peer.

**PRIVACY.md gaps (must fix for store compliance):**
- Claims "collects no data at all" / "no telemetry," but `diag()` writes peer device names and filenames to a retained 50-entry buffer **and** the platform logger (`P2PManager.kt:367-371,471,594`). On older Android these logs are world-readable; device names often contain a person's name.
- Omits two shipping transports — **Bluetooth SPP** and **Wi-Fi Aware**.
- The permissions table glosses `BLUETOOTH_*` and `ACCESS_FINE_LOCATION` as just "Wi-Fi/Nearby."
- Implies secure transfer while there is no E2E encryption and the QR carries credentials.

Update the policy to list all transports, describe local diagnostic logging + lifetime, map permissions accurately, and either add encryption or state plainly that confidentiality relies on transport-level security only. Also set `allowBackup=false` (B41) and gate verbose name/filename logging behind a debug flag.

---

## 9. Dead code & build hygiene

- **`P2pAppleHandler.kt`** — 534 lines of commented-out "Gmix" music-app code; the only live declaration (`PeerDevice`) is unused. **Delete.** (`P2pAppleHandler.kt:7`)
- **Desktop `WiFiDirectP2PM.desktop.kt`** — 344 lines, unreachable and non-functional (B11). **Delete or finish.**
- **`getManagerForTechnology` / `TransportCoordinator.schedule`** — fully implemented, called only from tests/KDoc. Either wire (§10) or mark staged-for-next-milestone so reviewers don't assume they're live.
- **Registry fields `priority`, `icon`, `discoveryMode`** — declared on all 9 transports, read off none; `icon` drags a Compose-Material dependency into a pure-logic file. **Remove or move `icon` to a UI mapping.** (`P2pTechnology.kt:30-40`)
- **`Platform.canScanQR`** — declared on every enum constant, zero readers; it's exactly the data that should drive QR show-vs-scan. **Wire it or delete it.** (`PlatformUtils.kt:16`)
- **iOS QR-screen leftovers** — `NearbyDeviceRow`, `SignalBars`, `SearchingSpinner` (camera-only screen, never invoked); `//TODO` no-op tap handlers in the pickers; half-wired `Platform.Web`. (`QRDiscoveryScreen.ios.kt:312,386`)
- **Vestigial protocol verbs** `REJECTED`/`CANCELLED` (B46).
- **Build config:** `BuildConfig.DEBUG=true` for release (B8); `armv7` device capability on an arm64 build (B27); `iosSimulatorArm64()` commented out (no Simulator builds — dev-velocity hit); over-broad `NSBonjourServices`; cocoapods deployment target 14 vs documented 16; `MpcP2PM`/iOS `LanMdnsP2PM` referenced with no Kotlin definition (expected from Swift) and `MpcP2PM` leaves `technology=null` (kills the upgrade badge for iOS↔iOS).
- **macOS-native build is dark for P2P** — `Platform.PC`, no callback, QR screen is an error stub. Decide: JVM-only desktop-mac, or wire a macOS callback.

---

## 10. Prioritized roadmap

Sequenced by the owner's priority order (**bug-free > seamless > speed > UI**). Each item carries an **effort** tag — **[wire]** = wire existing code, **[new]** = new code, no device needed, **[spike]** = needs a hardware spike — *and* a **risk** tag (`risk:LOW/MED/HIGH`) for how likely it is to slip or regress. Effort and risk diverge: item 13 (AEAD) is `[new]` but `risk:HIGH` (touches every frame + key exchange); item 6 (delete two gates) is also `[new]`-adjacent wiring but `risk:LOW`. Risk mirrors the §4 hack risk where an item maps to a hack.

### Tier 0 — Stop the bleeding (priority #1, do first)
1. **Auto-save on CRC-complete** — call `finalizeReceivedFilesAt(defaultSaveDir)` on `transferComplete`; fixes silent data loss (B1). **[wire]** (+ small per-platform default-dir **[spike-lite]**) · `risk:MED` (per-platform save-dir semantics — MediaStore/Files visibility — can surprise)
2. **Sender short-read abort** (B5) and **handshake/manifest timeout** (B29) — kill the two hang paths. **[new]** · `risk:LOW`
3. **iOS scan-failure recovery** — restart capture + broaden Retry; validate QR format before dialing (B6, B26, B25). **[new]** · `risk:LOW`
4. **`BuildConfig.DEBUG` from build type** (B8); **remove unused storage perms** (B9); **`allowBackup=false`** (B41). **[new]** · `risk:LOW`
5. **Manifest DoS bounds + TEXT cap** (B20, B19); **fallback modulo→clamp** with a terminal state (B28). **[new]** · `risk:LOW`

### Tier 1 — The two-click flow (priority #2)
6. **Delete the Send/Receive gate and the platform picker**; home = "select files to send"; route through `DirectionResolver` + caps. **[wire]** · `risk:LOW`
7. **Consume `qrData.capabilities` + add `QRData.platform` + fix the desktop QR** (HACK 8) — negotiate at decode time; interim shim picks the single best transport via `getManagerForTechnology` before the race exists. **[wire]** · `risk:LOW` (mind the `split(":", limit=7)` field-count bump, §4 HACK 8 caveat)
8. **Capability-bearing mDNS TXT + real `P2pPeer`** (HACK 9) — zero-QR same-LAN discovery; kills fabricated signal bars (B31) and the platform pick on discovery. **[spike]+[wire]** · `risk:MED` — writing the TXT is trivial wiring, but the load-bearing part is *readback* (flaky `getAttributes()` pre-API-33 → API-34 `registerServiceInfoCallback`; iOS `didUpdateTXTRecordData:` delegate), a per-version device spike, not parenthetical.
9. **One-tap/auto consent** — `await` before `writeManifestAck`, reusing `REJECTED` (no protocol change). **[wire]** · `risk:LOW`
10. **Unified multi-picker**; **back arrow**; **handshake-overlay timeout/cancel** (B22). **[new]** (iOS files-vs-Photos picker **[spike-lite]**) · `risk:LOW`
11. **Three-word pairing codes** (HACK 7) — erase desktop↔desktop friction. **[new]** · `risk:LOW`

### Tier 2 — The executor + speed (priority #3)
12. **`TransportRacer` + a uniform connect primitive** — split "channel established" from "start transferring" (per-race latch, not per-manager `transferBegun`); the dead `TransportCoordinator.schedule` finally drives connections; mis-ranked transports cost a stagger, not a dead end. Spikeable on desktop LAN, **no radio lab.** Includes a runtime Wi-Fi-Aware concurrency probe feeding `disruptsLocalConnectivity`. **[new]** + **[spike]** for the disruptive tail (hotspot/Direct teardown semantics) · `risk:HIGH` (concurrency races + the disruptive-teardown tail; the keystone, so a regression here is broad)
13. **App-layer AEAD** — X25519 + ChaCha20-Poly1305 after HELLO, SAS/QR-bound (B2, B3, B4); roll out behind min-version negotiation (B13 first). **[new]** · `risk:HIGH` (touches every frame + key exchange; a subtle bug is a silent security hole)
14. **Sequential bidirectional** — `DirectionResolver.BOTH` + phase-boundary swap (DoneFrame→ManifestFrame) + two-key ledger + phase cursor (protocol v4, §2.3). **[wire]** · `risk:MED` (resume across the phase boundary is the careful part)
15. **CRC single-file retry** (B14); **wire `WebReceiver` accept-loop + path token + bounded header** (B12, security). **[wire]/[new]** · `risk:MED` (the peek-replay seam + auth token must be right or the open-server gap ships)

### Tier 3 — Reach, robustness & polish (priority #3/#4)
16. **Persistent passive-advertise + listen for "always receivable"** (foreground + grace; iOS background is OS-capped — be honest). **[spike]** · `risk:MED` (iOS background limits cap the feature, not break it)
17. **BLE advertise/scan manager** for the reserved `Bluetooth` bit — the universal no-LAN beacon (Android↔Android realistic; iOS background largely impossible). **[spike]** · `risk:HIGH` (per-chip BLE behavior + iOS background near-impossible)
18. **Gear-shift UPGRADE frame** done right — hard cutover + explicit checkpoint-flush (HACK 4). **[spike]** · `risk:HIGH` (mid-transfer link cutover; the §4 HACK 4 seams)
19. **WebRTC relay** (HACK 12) for cross-network — the one deliberate stateless-server exception that beats AirDrop/Quick Share. **[spike]** · `risk:HIGH` (WKWebView gap, per-arch JVM binding, SCTP chunking adapter)
20. **Delete dead code** (`P2pAppleHandler`, desktop `WiFiDirectP2PM`, registry fields, `canScanQR`, iOS leftovers); **fix transport labels, accessibility, strings, FGS type/timing, PRIVACY.md.** **[new]** · `risk:LOW`
21. **`Platform` OS-family split** (mac/Win/Linux) so the negotiator can decide desktop no-LAN cells correctly. **[new]** · `risk:MED` (enum widening ripples through three `when(peerPlatform)` tables + the registry)

**The throughline:** Tier 0 makes it bug-free, Tier 1 makes it two clicks (mostly wiring of tested code), Tier 2's executor is the single change that makes the whole negotiator brain real and unlocks speed, and Tier 3 is the hardware-gated reach. The brain is done — the spinal cord is the work.
