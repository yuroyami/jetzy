# Performance Audit — Jetzy (Kotlin Multiplatform)

_Generated 2026-06-01 by a multi-agent audit: 17 finder agents (14 by file-slice + 3 by lens) → adversarial verification → dedup → synthesis, across ~14k LOC (commonMain + Android/iOS/desktop/macOS). Tests excluded (not runtime perf)._

**234 confirmed issues** — 36 high · 97 medium · 101 low. A further 41 candidates were rejected as false positives by the verification pass. Each finding was re-checked against the actual code by a second agent.

## Resolution status (2026-06-02)

**Fixed across 5 commits on `master`** — `ff05a2f`, `a91fcf2`, `121b372`, `25725d1`, `833bf08` (~100 distinct fixes):
- Transfer hot loop: ~10 Hz emission throttle, monotonic clock (no per-chunk NSDate), per-file ledger cadence, device-name memoization, derivedStateOf count.
- Compose: hoisted per-frame/per-recomposition allocations into `remember`, keyed lists, dropped per-frame O(n) scans (RadarView, TransferScreen, file-pickers, buttons, theme, viewmodel flows).
- Transport mediums: shared `SelectorManager` (leak fix), `MCSession.delegate` cleared (retain cycle), `delay`/`yield` backoff on busy-spin pumps, cancellable poll loops, cached lookups.
- Platform/models: memoized device name & storage path, `clock_gettime` on iOS, trimmed serialization allocations.

**Every commit compiles on all four targets** (desktop/JVM, androidTarget, iosArm64, macosArm64); the 3 unit tests (incl. `ResumeCrcTest`) pass.

**Deliberately NOT changed (documented judgment calls):**
- ~20 transport tweaks the verifier/agents judged too risky to land on compile-only verification.
- ~17 marginal / cold-path micro-allocations rated negligible.
- Transfer-list virtualization — would restructure the GlassCard layout; the source-side throttle already removes the recomposition-storm motivation.
- `itemsRECEIVED` SnapshotStateList → StateFlow — the snapshot list provides cross-thread safety a plain list wouldn't; ~nil perf gain.
- Text-entry in-memory accumulation — complicates the resume/CRC path; text entries are small by contract.
- `Theme.kt` dead color-palette removal — out of scope for a performance pass.
- `QRDiscoveryScreen.ios.kt` findings — file has unrelated uncommitted work in progress; left untouched.

> ⚠️ The transport-medium changes (`25725d1`) are compile-verified only — their real socket/threading behavior on physical Wi-Fi/Bluetooth radios was **not** exercised here. Validate throughput/stability on-device before release.

---

## Executive summary

The app is functionally complete but its performance is dominated by one cascading hot path: the per-512KB-chunk send/receive loop in P2PManager.kt (lines ~422-425, 615, 836), which on every chunk does a full O(n) list copy via updateAt's toMutableList(), writes per-chunk StateFlows (fileEntries/transferProgress), allocates a fresh ReceiverFileState, and calls generateTimestampMillis() (an NSDate allocation on iOS). Because those StateFlows are unthrottled, they push Compose recomposition at chunk rate straight into TransferScreenUI.kt, where an O(n) .count{} scan and an un-keyed forEachIndexed re-render every file row — turning a single transfer into O(files × chunks) work plus sustained GC pressure that directly caps throughput. The second-worst tier is structural lifecycle and discovery-screen waste: a CoroutineScope (p2pScope) that is never cancelled and leaks all coroutines, desktop peer-polling that forks a new wpa_cli/PowerShell process every 2-3 seconds, and the RadarView/PeerDiscoveryScreen which rebuilds InfiniteTransitions, runs O(n) linear peer scans, and allocates Stroke/Color/Size objects inside drawBehind on every animation frame. A third, pervasive tier is allocation-in-composition (FontFamily, Brush, RoundedCornerShape, ButtonColors, derivedStateOf, and Color.copy all rebuilt without remember) spread across nearly every UI file. The biggest single win is to make the transfer loop allocation-free and rate-limited — coalesce per-chunk StateFlow emissions to ~10-15 Hz, replace updateAt's list copy with an in-place SnapshotStateList write, mutate ReceiverFileState rather than reallocating, and cache the timestamp — which simultaneously kills the worst GC churn, the recomposition storm, and the throughput ceiling. After that, cancel p2pScope, switch desktop polling to a long-lived process/event source, and key the LazyColumn / fix the discovery drawBehind allocations.

The Android Wi-Fi Aware / Wi-Fi Direct / Hotspot transports (audited in a follow-up pass) add a fourth pattern: their read/write **pumps busy-spin at full CPU** when `readAvailable` returns 0, and flush per chunk — burning a core and serializing syscalls during every transfer on those mediums.

## Recurring themes

- Per-chunk allocation in the transfer hot loop: full list copies (updateAt's toMutableList), ReceiverFileState objects, and NSDate timestamps are allocated on every 512KB chunk.
- Unthrottled per-chunk StateFlow writes (transferProgress/fileEntries) drive Compose recomposition at chunk rate with no rate limiting.
- O(n) work re-run on every recomposition/frame: .count{} scans, linear peers.firstOrNull{} lookups, and full peer-list rebuilds.
- UI lists rendered with un-keyed forEachIndexed instead of keyed LazyColumn items, defeating recycling and forcing full re-reconciliation per update.
- Heavyweight objects (FontFamily, Brush, RoundedCornerShape, ButtonColors, Stroke, Color.copy) allocated in composition/drawBehind without remember, churning GC every frame.
- Process-spawn and syscall polling: desktop forks wpa_cli/PowerShell every few seconds; iOS/macOS re-run uname/NSHost/NEHotspot lookups uncached.
- Leaked or unbounded resources: p2pScope never cancelled, SelectorManagers allocated per call, resolvingDelegates list never pruned.
- Per-chunk/per-write flush() on Bluetooth and NSOutputStream sinks defeats buffering and serializes throughput.
- Transport read/write pumps busy-spin at full CPU on their IO threads when readAvailable returns 0 (Android & iOS Wi-Fi Aware, desktop Bluetooth) instead of suspending.
- Derived display labels (sizeLabel, typeLabel, toHumanSize) are recomputed as fresh String allocations on every recomposition.

## Top issues to fix first

1. **updateAt copies the entire fileEntries list (toMutableList) on every 512KB chunk** — `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836`
   - This single helper is called on both send and receive paths every chunk, making each transfer O(files × chunks) in allocation; replacing it with an in-place write is the highest-leverage fix and is shared by many of the other top issues.
2. **Per-chunk fileEntries + transferProgress StateFlow emissions fire recomposition on every chunk** — `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:422`
   - Unthrottled chunk-rate emission is the root cause of the whole UI recomposition storm; coalescing to ~10-15 Hz instantly relieves every downstream transfer-screen hotspot.
3. **Per-chunk ReceiverFileState object allocated in the receive hot loop** — `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615`
   - A new data-class instance per chunk on the receive side compounds the GC pressure that caps inbound throughput; mutating existing state instead removes a per-chunk allocation.
4. **completedFiles computed with O(n) .count{} scan on every recomposition** — `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:166`
   - Driven by chunk-rate StateFlow updates, this linear scan runs constantly during transfer; deriving it once (derivedStateOf) removes O(files) work per frame on the active screen.
5. **fileEntries rendered with un-keyed forEachIndexed inside a single LazyColumn item** — `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:188`
   - No keys and no virtualization means every row recomposes on every chunk; converting to keyed items{} confines recomposition to the rows that actually changed.
6. **Per-chunk NSDate allocation on iOS in generateTimestampMillis** — `shared/src/iosMain/kotlin/jetzy/utils/PlatformUtils.ios.kt:31`
   - Called every chunk in both loops and crossing the Kotlin/ObjC bridge to allocate an NSDate whose value is discarded ~99% of the time; trivial to replace with a cheaper monotonic clock for a direct iOS throughput gain.
7. **Crc32.update processes one byte at a time** — `shared/src/commonMain/kotlin/jetzy/managers/Crc32.kt:16`
   - CRC runs over every byte of every transferred file, so a slice-by-8 table upgrade gives a measurable end-to-end throughput improvement on the genuinely hottest byte-level path.
8. **p2pScope CoroutineScope is never cancelled — leaks all coroutines** — `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:67`
   - Every discarded manager leaks its coroutines (polling, pumps, watchdogs), causing unbounded resource growth across the app's lifetime; a correctness-grade leak that's cheap to fix.
9. **Linux peer-poll spawns a new wpa_cli process every 2 seconds (per peer too)** — `shared/src/desktopMain/kotlin/jetzy/managers/WiFiDirectP2PM.desktop.kt:108`
   - Forking an OS process on a 2s loop (and per peer) is enormous overhead for idle discovery; a long-lived process or event source eliminates a constant background CPU drain on desktop.
10. **Windows peer-poll spawns a new PowerShell process every 3 seconds** — `shared/src/desktopMain/kotlin/jetzy/managers/WiFiDirectP2PM.desktop.kt:155`
   - PowerShell startup is especially expensive, so re-launching it every 3s with an inline script makes desktop discovery a heavy idle cost; same fix shape as the Linux poller.
11. **Per-peer InfiniteTransition + drawBehind with O(n) peers.firstOrNull{} scan every frame** — `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:382`
   - RadarView re-creates animations and runs a linear peer scan plus Color/Stroke/Size allocations inside drawBehind for every peer every frame, making the idle discovery screen O(n) per-frame; the most expensive non-transfer UI hotspot.
12. **Per-chunk flush() on raw Bluetooth OutputStream destroys throughput** — `shared/src/androidMain/kotlin/jetzy/managers/BluetoothSppP2PM.kt:270`
   - Flushing every chunk forces a round-trip and defeats buffering, directly throttling the Bluetooth transport; removing it is a one-line change with a large throughput payoff (mirrored by the MPC/Wi-Fi Aware per-write flushes).
13. **Five independent snapshotFlow + filterIsInstance flows re-scan elementsToSend on every mutation** — `shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt:284`
   - Every list mutation triggers five full-list re-scans that never stop (SharingStarted.Lazily), multiplying VM work on the file-picking path; consolidating to one partitioning flow removes 5x redundant scanning.
14. **Per-item LaunchedEffect with fixed 300ms delay forces a full Coil reload for every image cell** — `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickPhotosSubscreen.kt:127`
   - Forcing a reload per cell makes the photo grid re-decode images unnecessarily, causing jank and memory spikes while scrolling the picker; removing the artificial delay/reload is a clear UX win.
15. **AppTypography and FontFamily allocated on every recomposition** — `shared/src/commonMain/kotlin/jetzy/theme/Type.kt:11`
   - This is the canonical instance of the most widespread theme problem (FontFamily/Brush/Shape rebuilt without remember across many files); fixing it once at the theme root and applying the pattern broadly removes pervasive per-recomposition allocation app-wide.

_Also newly surfaced (Android transports): spin-idle Wi-Fi Aware write pump (`shared/src/androidMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:384`), per-chunk flush (`:389`), and busy-wait in Wi-Fi Direct `connectToPeer` (`shared/src/androidMain/kotlin/jetzy/managers/WiFiDirectP2PM.kt:241`)._

## Severity breakdown by subsystem

| Subsystem | High | Med | Low | Total |
|---|---:|---:|---:|---:|
| Transport · Core engine | 10 | 18 | 15 | 43 |
| Transport · Mediums | 8 | 20 | 23 | 51 |
| Transport · Negotiation | 0 | 2 | 3 | 5 |
| ViewModel / state | 0 | 3 | 1 | 4 |
| UI · Transfer screen | 4 | 17 | 6 | 27 |
| UI · Discovery / QR | 8 | 16 | 12 | 36 |
| UI · Main / shared | 2 | 8 | 13 | 23 |
| UI · File picking | 2 | 3 | 10 | 15 |
| UI · Theme | 1 | 1 | 3 | 5 |
| Lifecycle / entry | 0 | 1 | 2 | 3 |
| Models / utils | 1 | 7 | 12 | 20 |
| Permissions | 0 | 1 | 1 | 2 |
| **Total** | **36** | **97** | **101** | **234** |

---

## All findings

### Transport · Core engine — 43 (10H / 18M / 15L)

#### 🔴 HIGH · Per-chunk StateFlow writes for fileEntries and transferProgress fire Compose recomposition on every 512 KB chunk

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:422` · _Coroutine/Flow misuse_
- **Why it's slow:** Lines 422-423 (send loop) and 617-618 (receive loop) unconditionally write to fileEntries (via updateAt) and transferProgress.value on every chunk with no rate-limiting. Each StateFlow write schedules a Compose snapshot invalidation. transferSpeed is already gated behind a 1-second window (line 427), but fileEntries and transferProgress are not, so they fire at full I/O throughput — potentially hundreds of times per second on a fast link.
- **Fix:** Apply the same 1-second (or 100ms) throttle already used for transferSpeed to fileEntries and transferProgress updates. Track the raw progress fraction in a plain local variable and only assign to the StateFlow inside the elapsed >= 1000L block. A progress bar never needs sub-second resolution.

#### 🔴 HIGH · Per-chunk ReceiverFileState allocation written to ledger on every 512 KB read

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _Allocations / GC churn in hot path_
- **Why it's slow:** Inside the receiver inner loop a new ReceiverFileState data-class object is allocated and stored into the ledger map on every chunk (line 615). For a 1 GB file at 512 KB buffer size, this is ≥2000 heap allocations of a data class carrying a Path reference. The ledger is only read during resume at reconnect time, not during the active transfer.
- **Fix:** Update the ledger only at file boundaries (after each file completes, not per chunk), since the ledger is only consumed during resume handshaking. This eliminates all per-chunk ledger allocation with no correctness impact.

#### 🔴 HIGH · ReceiverLedger updated (map put + ReceiverFileState allocation) every chunk in the hot receive loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _GC churn in hot path_
- **Why it's slow:** Inside the per-chunk while loop in receiveFiles, line 615 executes receiverLedger[index] = ReceiverFileState(tempPath, fileRead, entry.sizeBytes) on every 512 KB chunk. This allocates a new data-class instance and performs a HashMap put on each iteration — ~200 allocations per 100 MB file. The ledger is only consumed by computeResumePoint (on reconnect) and cleanup; sub-second granularity provides no resume accuracy benefit.
- **Fix:** Update the ledger at a coarser granularity: write it once per second alongside the speed-window update (inside the elapsed >= 1000L branch at line 622) or only after each file completes. The resume byte accuracy penalty is bounded by one speed window of data.

#### 🔴 HIGH · updateAt() copies the entire List<FileTransferEntry> on every chunk — runs per-chunk inside the transfer hot loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:832` · _Allocations / GC churn_
- **Why it's slow:** updateAt (line 832-836) calls value.toMutableList() — a full ArrayList copy — modifies one element, and assigns back. It is called inside the send chunk loop at line 422 and the receive chunk loop at line 617 on every read/write iteration. For a 1 GB transfer with 512 KB chunks this is ~2048 full List copies, each copying all FileTransferEntry references. FileTransferEntry.copy() also allocates a new data-class instance per call. This is the real hot-path allocation in the codebase.
- **Fix:** Rate-limit the UI update to at most once every 100–200 ms by gating on a timestamp: only call updateAt when `now - lastUiUpdate >= 100`. Alternatively, replace List<FileTransferEntry> with individual per-file MutableStateFlow<FileTransferEntry> objects so only the active entry's flow is updated.

#### 🔴 HIGH · Per-chunk full list copy in updateAt — O(n) allocation on every chunk

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836` · _Allocations / GC churn_
- **Why it's slow:** updateAt (line 836) always calls value.toMutableList(), which copies the entire list, mutates one entry, and publishes a new list to the StateFlow. This runs on every 512 KB chunk in both the send loop (line 422) and receive loop (line 617). For a 1 GB file this is ~2000 full list copies, each allocating a new ArrayList and a new FileTransferEntry via .copy(), immediately discarding the old list. With many files in the manifest the cost scales with N files.
- **Fix:** Track per-file progress in individual MutableStateFlow<FileTransferEntry> fields rather than in one shared list StateFlow, so a chunk update for file #3 never touches file #1's memory. Alternatively, switch fileEntries backing store to a SnapshotStateList (read from the main thread) updated via a dedicated main-thread dispatcher, which allows in-place mutation without copying.

#### 🔴 HIGH · updateAt() allocates a full List copy on every chunk during file transfer

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836` · _Allocation / GC churn in hot path_
- **Why it's slow:** updateAt() at line 832-837 calls value.toMutableList() on every invocation, creating a full ArrayList copy of the entire fileEntries list. It is called every 512 KB chunk during both send (line 422) and receive (line 617), producing continuous garbage during active transfer. fileEntries is declared as a StateFlow<List<FileTransferEntry>> (not a SnapshotStateList), so index-based mutation requires a full copy.
- **Fix:** Change fileEntries backing store from MutableStateFlow<List<FileTransferEntry>> to mutableStateListOf<FileTransferEntry>() (already imported at line 4) so element replacement is O(1) with no list copy. Alternatively, keep separate per-file MutableStateFlow<FileTransferEntry> instances, or batch progress updates to reduce copy frequency.

#### 🔴 HIGH · fileEntries.updateAt() creates a full MutableList copy per chunk in the transfer loop (P2PManager)

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836` · _Allocations / GC churn in hot path_
- **Why it's slow:** updateAt() calls value.toMutableList(), copies all N FileTransferEntry references, mutates one, and publishes a new list as a StateFlow value. It is called unconditionally on every 512 KB chunk at lines 422 (send) and 617 (receive). For a 10-file transfer at 250 Mbps, this is roughly 600 List copies per second, each copying 10 object references. The copies themselves are cheap, but the StateFlow observer on the UI thread receives 60 emissions per second per file in transfer, driving redundant recompositions if the UI observes fileEntries.
- **Fix:** Rate-limit progress updates to at most once per second using the same elapsed >= 1000L guard already used for the speed window: only call updateAt when the speed window would update, or set a separate lastProgressUpdateMs check. For final states (Done, Failed) update immediately. This reduces recomposition from 60Hz to 1Hz with no visible user impact.

#### 🔴 HIGH · Per-chunk StateFlow list copy via updateAt() allocates a new List<FileTransferEntry> on every 512 KB chunk

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836` · _Allocations / GC churn in hot path_
- **Why it's slow:** updateAt() at line 836 calls value.toMutableList() — copying the entire file-entry list — then reassigns the StateFlow on every call. The critical hot-path calls are at line 422 (inside the innermost per-chunk send loop) and line 617 (inside the innermost per-chunk receive loop). Every 512 KB chunk triggers a full list copy and a StateFlow emission that can wake Compose collectors. Other updateAt() calls (lines 372, 383, 443, 450, 454, 555, 564, 640, 656) are per-file, not per-chunk, and are not the primary concern.
- **Fix:** Rate-limit the per-chunk progress update so it only emits when progress changes by a meaningful threshold (e.g. >=0.5% or >=250 ms elapsed) — mirroring the speed-window throttle already present for transferSpeed. The list copy frequency drops from O(chunks) to O(files * updates_per_file), eliminating thousands of copies per transfer.

#### 🔴 HIGH · fileEntries list copied in full on every chunk (O(n) allocation per packet)

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836` · _Allocations / GC churn in hot path_
- **Why it's slow:** updateAt() calls value.toMutableList() then does an indexed set, allocating a full ArrayList copy on every call. The inner I/O loop at lines 422 and 617 calls updateAt() on every read() iteration — every 512 KB chunk — so this copy runs continuously during transfer. With the 512 KB buffer, a 1 GB transfer produces ~2000 copies, each of size N (number of files). The copy is proportional to N files, not chunk count, so for 100-file transfers the churn is meaningful.
- **Fix:** Hoist fileEntries to MutableStateFlow<Array<FileTransferEntry>> (array set is in-place O(1)) or use kotlinx.collections.immutable PersistentList.set(). Alternatively, throttle UI updates: only emit fileEntries once per second alongside transferSpeed, and update transferProgress separately as it is already a separate StateFlow.

#### 🔴 HIGH · Per-chunk list copy + StateFlow emit on every 512 KB chunk in hot transfer loops

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836` · _GC churn in hot path_
- **Why it's slow:** updateAt() is called on every chunk iteration of sendFiles (line 422) and receiveFiles (line 617). Each call does value.toMutableList() — allocating a new MutableList that copies all N FileTransferEntry references — then assigns back, emitting the new list via StateFlow. With a 512 KB buffer over a 100 MB file (~200 chunks/file), this produces hundreds of short-lived List allocations per second and wakes every StateFlow collector on every chunk. markAllNonDoneAsFailed (line 816) applies the same full-list-copy pattern, though on an infrequent error path. The hot-path cost is the per-chunk updateAt inside the while loops.
- **Fix:** Batch the progress-only updates: emit a full FileTransferEntry copy only when status changes (Pending→Active, Active→Done/Failed); use a separate lightweight StateFlow<LongArray> indexed by file for bytesTransferred so that the per-chunk update is a single array store with no list allocation. Alternatively, gate fileEntries updates inside the same 1-second speed window already used for transferSpeed (lines 427-431).

#### 🟠 MED · CRC32 update: buf[i].toInt() followed by and 0xFF mask on every byte in the hot loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/Crc32.kt:17` · _Algorithmic inefficiency_
- **Why it's slow:** The inner CRC loop at line 17 uses (v xor buf[i].toInt()) and 0xFF. The toInt() sign-extends the Kotlin signed Byte, and the and 0xFF correctly masks the sign bits. The mask is already present so the result is always correct. The 'wasted mask' claim is accurate — using buf[i].toUByte().toInt() would zero-extend without needing the mask — but both forms compile to effectively the same JVM bytecode (baload + i2i + iand). The real opportunity is the claim that java.util.zip.CRC32 uses hardware acceleration: on Android this is backed by native code and on JVM by the hardware CRC32 instruction, giving 5-10x throughput improvement on GB-scale transfers.
- **Fix:** On JVM/Android targets, use java.util.zip.CRC32 via an expect/actual pattern. The kotlin-native Crc32 class can remain for iOS. On JVM, java.util.zip.CRC32.update(byte[], offset, len) is hardware-accelerated and processes the same 512 KB buffer in a fraction of the time. The toInt() vs toUByte() micro-optimization is not meaningful.

#### 🟠 MED · FileTransferEntry.copy() allocates a new data class on every chunk in both send and receive loops

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:422` · _GC churn in hot path_
- **Why it's slow:** On every 512 KB chunk, updateAt calls it.copy(bytesTransferred = bytesTransferred) (sender line 422) or it.copy(bytesTransferred = fileRead) (receiver line 617), allocating a new FileTransferEntry. Combined with the toMutableList() inside updateAt, this produces two heap allocations per chunk solely for the progress update. This is a sub-finding of the updateAt pattern in Finding 1 but independently valid: even if the list copy were removed, the FileTransferEntry copy would remain without the batching fix.
- **Fix:** Track in-flight bytesTransferred in a plain LongArray or map keyed by file index and emit only when status changes. Only copy the full FileTransferEntry when status transitions, not on every chunk.

#### 🟠 MED · transferProgress.value written on every chunk — boxes a new Float per-chunk

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:423` · _Allocations / GC churn_
- **Why it's slow:** transferProgress.value = totalBytesSent.toFloat() / totalBytes.coerceAtLeast(1L) is called on every chunk at lines 423 (send) and 618 (receive). MutableStateFlow<Float> stores a boxed Float on the JVM. With 512 KB chunks and a 1 GB file this is ~2048 Float boxing operations per transfer. The same rate-limiting fix for updateAt (finding 13) would also address this, since both are in the same loop body.
- **Fix:** Apply the same timestamp-based rate-limit gate as suggested for updateAt: only update transferProgress when at least 100 ms has elapsed since the last update. The existing speedWindowStart timestamp can be reused for this check.

#### 🟠 MED · transferProgress StateFlow assigned on every chunk with no rate-limiting

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:423` · _Unnecessary StateFlow churn in hot path_
- **Why it's slow:** transferProgress.value = ... is set on every 512 KB chunk (sender line 423, receiver line 618). StateFlow does conflate internally so values do not queue, but each assignment wakes all collectors synchronously if they are running on the same dispatcher, and schedules a snapshot notification on the main thread. At LAN speeds (>100 MB/s), this can fire hundreds of times per second, causing constant recomposition of the progress UI even though the user cannot perceive updates faster than ~30 fps.
- **Fix:** Gate the transferProgress assignment inside the existing elapsed >= 1000L speed-window branch (lines 427-431). A single extra line there provides all the throttling needed.

#### 🟠 MED · Per-chunk StateFlow write of transferProgress allocates a boxed Float every iteration

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:423` · _Allocation / GC_
- **Why it's slow:** Lines 423 and 618 write `transferProgress.value = totalBytesSent.toFloat() / ...` inside the per-chunk inner loops. On Kotlin/JVM (Android), StateFlow<Float> stores a boxed Float object, so each assignment allocates. On Kotlin/Native (iOS) the JVM boxing rules do not apply, so the impact there is zero. The real waste is that the UI only needs ~1 update per second, yet this fires ~2 000 times per 1 GB transfer.
- **Fix:** Gate the progress update behind the same elapsed-time check already used for transferSpeed (lines 425–431): only update transferProgress when `elapsed >= 1000L`. This eliminates ~999/1000 redundant writes and all associated boxing on Android.

#### 🟠 MED · generateTimestampMillis() called on every chunk for speed window; iOS implementation allocates NSDate

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:425` · _Algorithmic inefficiency_
- **Why it's slow:** Line 425 (send loop) and line 620 (receive loop) call generateTimestampMillis() unconditionally on every chunk, then check if the elapsed time exceeds 1 second. The iOS implementation (PlatformUtils.ios.kt line 31) creates NSDate() — an Objective-C Foundation object — on every call. Since the window fires at most once per second but chunks arrive much faster, roughly 99% of these NSDate allocations are discarded. For a fast Wi-Fi transfer processing hundreds of 512 KB chunks per second this creates hundreds of short-lived ObjC objects per second on iOS.
- **Fix:** Move the speed calculation to a separate coroutine that ticks on a 1-second delay using kotlinx.coroutines.delay, sampling bytesMovedTotal and computing throughput independently. This removes the timestamp call from the hot chunk loop entirely on all platforms. The plain local-variable approach (check elapsed) is still correct but wastes the NSDate allocation on iOS.

#### 🟠 MED · generateTimestampMillis() called unconditionally on every chunk (iOS: NSDate allocation per call)

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:425` · _GC churn in hot path_
- **Why it's slow:** In sendFiles (line 425) and receiveFiles (line 620), generateTimestampMillis() is called unconditionally on every 512 KB chunk to compute elapsed. On iOS the implementation at PlatformUtils.ios.kt:31 is (NSDate().timeIntervalSince1970 * 1000).roundToLong(), allocating an NSDate Objective-C object on each call. Over a 100 MB transfer this is ~200 NSDate allocations per file. The Android implementation (System.currentTimeMillis()) is heap-free but the per-chunk unconditional call is still unnecessary since the result is only used when elapsed >= 1000L.
- **Fix:** On iOS, use clock_gettime(CLOCK_MONOTONIC) via posix interop instead of NSDate — it is allocation-free. Regardless of platform, consider sampling the time only once per N chunks (e.g. every 10) to amortize the syscall cost, since the 1-second window check tolerates a few-chunk lag.

#### 🟠 MED · Per-chunk ReceiverFileState heap object allocation in receive loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _Allocations / GC churn_
- **Why it's slow:** On every received chunk the receive loop at line 615 stores receiverLedger[index] = ReceiverFileState(tempPath, fileRead, entry.sizeBytes). Two of the three fields (tempPath and entry.sizeBytes) are constant for the lifetime of the file; only fileRead changes. A new data class object is allocated each iteration purely to update one Long field. With a 512 KB buffer and a 1 GB file this is ~2000 unnecessary short-lived allocations per file, though the absolute heap impact is small (three fields per object).
- **Fix:** Store a single mutable wrapper object per file in the ledger (e.g., a non-data class with a var bytesWritten field) and update bytesWritten in place instead of replacing the object every chunk. Or maintain a separate mutableMapOf<Int, Long> for bytesWritten and only store the immutable (tempPath, sizeBytes) pair once.

#### 🟠 MED · receiverLedger updated on every 512 KB chunk — boxing Int key and allocating ReceiverFileState per chunk

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _Allocation / GC churn in hot path_
- **Why it's slow:** Inside the receive inner loop at line 615, `receiverLedger[index] = ReceiverFileState(tempPath, fileRead, entry.sizeBytes)` is executed for every 512 KB chunk. The Int key is autoboxed to Integer for the HashMap, and a new ReceiverFileState data class (with immutable tempPath and sizeBytes) is allocated each time. The ledger's purpose is resume bookkeeping — only bytesWritten changes per chunk.
- **Fix:** Update the ledger at a coarser granularity (e.g., every N chunks or on file completion) since tempPath and sizeBytes are constant per file. Alternatively, store a LongArray of bytesWritten values indexed by file position and update the full ReceiverFileState only on file completion, reducing per-chunk allocation to a primitive array write.

#### 🟠 MED · receiverLedger written with a new ReceiverFileState object on every 512 KiB chunk (P2PManager)

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _Allocations / GC churn in hot path_
- **Why it's slow:** Inside the receive inner loop (line 615), receiverLedger[index] = ReceiverFileState(tempPath, fileRead, entry.sizeBytes) allocates a new data class instance on every chunk. tempPath and entry.sizeBytes are invariant within the file's loop; only fileRead changes. At 512 KB chunks for a 1 GB file this allocates 2048 ReceiverFileState objects that are immediately superseded. The object is small (two Longs and a Path reference), so GC pressure is moderate but real.
- **Fix:** Track fileRead in a local Long variable and update receiverLedger only at meaningful checkpoints — e.g. every 16 MB or at file completion — since the ledger is only needed for crash-resume, not for real-time UI. The tempPath and entry.sizeBytes never change, so they need not be re-written every chunk.

#### 🟠 MED · ReceiverFileState data class allocated per 512 KB chunk inside the innermost receive loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _Allocations / GC churn in hot path_
- **Why it's slow:** Inside the innermost per-chunk receive loop (while (fileRead < entry.sizeBytes)), line 615 allocates a new ReceiverFileState data class on every 512 KB chunk to update the ledger. Since tempPath and entry.sizeBytes are constant for the file's duration, only fileRead changes per chunk. The mutableMapOf<Int, ReceiverFileState> also boxes the Int key on every put. At ~100 chunks/sec at Wi-Fi speeds this produces sustained allocation pressure from both the data class and the boxed Int.
- **Fix:** Track only the mutable field: store a Long (bytesWritten) keyed by index in the ledger, and re-derive tempPath and sizeBytes from their source variables (already in scope) at resume time. This eliminates the per-chunk ReceiverFileState allocation and reduces the map to Map<Int, Long>. Alternatively, update the ledger only every N chunks or every M bytes to reduce allocation frequency without changing the resume contract.

#### 🟠 MED · Per-chunk ReceiverFileState object allocation inside receive inner loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _Allocation / GC_
- **Why it's slow:** Inside the receive while-loop (line 603), every iteration executes `receiverLedger[index] = ReceiverFileState(tempPath, fileRead, entry.sizeBytes)` (line 615). Only `fileRead` changes per chunk; `tempPath` and `entry.sizeBytes` are constant for the file. A new data class heap object is allocated and the old one discarded every 512 KB solely to update one Long field.
- **Fix:** Store only the mutable counter per ledger slot — e.g. a `LongArray` keyed by index for bytesWritten — or at minimum skip the ledger update mid-file and only update it once per file completion (the ledger is only consulted at resume negotiation time, not during the transfer loop).

#### 🟠 MED · Text file content is re-read from disk into memory after already being fully received via the network buffer

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:648` · _Algorithmic inefficiency_
- **Why it's slow:** Lines 647-653 re-open tempPath and read all its bytes via readByteArray() for TEXT entries immediately after the file was written and flushed. The bytes that were received from the network were written chunk-by-chunk (line 606) and immediately discarded from buf — they were never accumulated in memory. The extra disk read is the only way to obtain the full file content given the current architecture, so this is not strictly redundant; it is an unavoidable consequence of the streaming write design. However, for small text files (a common case), accumulating the bytes in a ByteArrayOutputStream during receive would avoid the round-trip to disk entirely.
- **Fix:** For TEXT entries where entry.sizeBytes is below a threshold (e.g., 1 MB), accumulate received bytes into a ByteArrayOutputStream during the while loop instead of discarding them after sink.write(). After the loop, decodeToString() from the in-memory buffer and skip the extra disk read. Guard with if (entry.entryType == EntryType.TEXT && entry.sizeBytes <= TEXT_PREVIEW_MAX) before allocation.

#### 🟠 MED · Text file re-read from disk after just writing it to reconstruct textContent

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:649` · _IO_
- **Why it's slow:** At lines 648–653, after fully receiving and closing a TEXT entry's sink, the code immediately reopens the same temp file via SystemFileSystem.source(tempPath).buffered() and reads the entire content back into a ByteArray just to decode it as a String. This doubles disk IO for text transfers: one write pass and one full read-back pass. The allocation of a ByteArray mirroring the whole file is also wasteful.
- **Fix:** Accumulate received text chunks into a ByteArrayOutputStream (or append to a ByteArray builder) during the receive loop instead of writing through the sink, and decode once at loop exit. Text entries are guaranteed small by contract, so keeping them in memory during receive is safe and avoids both the extra file open and the redundant read.

#### 🟠 MED · itemsRECEIVED is a Compose SnapshotStateList mutated from an IO-dispatcher coroutine

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:663` · _Coroutine/Flow misuse_
- **Why it's slow:** mutableStateListOf() at line 145 returns a Compose SnapshotStateList. It is written via itemsRECEIVED.add() at line 663 from p2pScope, which uses PreferablyIO (confirmed as Dispatchers.IO on all platforms). This is a threading violation: Compose snapshot writes are not thread-safe without wrapping in Snapshot.withMutableSnapshot(). The mutation happens once per completed file (not per chunk), so the practical crash risk is lower than a per-chunk violation, but the invariant is still broken.
- **Fix:** Replace mutableStateListOf with MutableStateFlow<List<ReceivedItem>>(emptyList()) backed by a plain mutable list updated under a lock, and expose it as StateFlow<List<ReceivedItem>> so Compose collects it safely via collectAsState().

#### 🟠 MED · itemsRECEIVED is a SnapshotStateList mutated from the IO dispatcher

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:663` · _Cross-thread snapshot mutation_
- **Why it's slow:** itemsRECEIVED is declared as mutableStateListOf<ReceivedItem>() at line 145 and is mutated (add at line 663, clear in purgeUnsavedReceivedFiles) from p2pScope which runs on Dispatchers.IO. Compose snapshot writes are technically permitted from any thread, but each write acquires the global snapshot lock and schedules invalidation on the main thread. For a per-file (not per-chunk) add, this is one lock acquisition per received file, so the contention concern is minor in practice. The more substantive concern is that it creates a confusing threading contract — readers on the main thread see mutations without explicit synchronization.
- **Fix:** Replace with a MutableStateFlow<List<ReceivedItem>> updated via a copy-on-write approach from the IO coroutine. The UI collects the StateFlow; no snapshot lock involvement, cleaner threading contract.

#### 🟠 MED · getDeviceName() called 3–4 times per handshake without caching — triggers blocking DNS/uname on desktop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:701` · _Synchronous network/disk on IO thread; redundant re-computation_
- **Why it's slow:** performHandshakeAsSender (line 701) and performHandshakeAsReceiver (line 711) each call getDeviceName() once. receiveFiles() calls it 3 more times (lines 514, 522, 532) inside ManifestAckFrame branch expressions. On Desktop, getDeviceName() calls InetAddress.getLocalHost().hostName which involves a potentially blocking DNS lookup. The result never changes within a session and is never cached.
- **Fix:** Cache the device name in a top-level lazy val or cache it in the P2PManager instance at initialization time. All five call sites can reference a single cached value.

#### 🟠 MED · fileEntries.updateAt() copies entire list (toMutableList) on every chunk in the hot transfer loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:836` · _Allocation / GC churn in hot path_
- **Why it's slow:** `updateAt` calls `value.toMutableList().also { it[index] = transform(it[index]) }` and is invoked inside the per-chunk write loop at line 422 (send) and line 617 (receive). With a 512 KB chunk size, at Wi-Fi speeds (100+ Mbps) this produces ~25 list copies per second; at BT SPP speeds (~250 KB/s) roughly 0.5 per second. Each copy allocates a new `MutableList` plus object references for all `FileTransferEntry` items. The `FileTransferEntry.copy(bytesTransferred = ...)` lambda also creates a new data class instance per invocation. The real-time UI concern is valid at high-speed transfers.
- **Fix:** Separate progress tracking from status tracking: use a `MutableStateFlow<LongArray>` indexed by file position for `bytesTransferred` updates (updated per-chunk) and reserve `fileEntries` updates only for status changes (Pending → Active → Done). The UI collects the `LongArray` flow for progress bars and the `fileEntries` flow for status icons. This reduces list copies from O(chunks) to O(files).

#### 🟡 LOW · AckStatus.fromCode and FileAck.fromCode do a linear scan over enum entries on every per-file ACK

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/JetzyProtocol.kt:52` · _Algorithmic inefficiency_
- **Why it's slow:** Both fromCode companions at lines 51-52 and 62-63 call entries.firstOrNull { it.code == c }, a linear scan over a 4-entry and 3-entry enum respectively. FileAck.fromCode is called once per file at line 440; AckStatus.fromCode is called once per session at line 184. The enums are tiny and this is not on the chunk-level hot path. The 'allocates an iterator' claim is mildly overstated — Kotlin enum entries is a List with a known-constant listIterator, but it does allocate.
- **Fix:** Replace with a fixed-size lookup array: private val BY_CODE = arrayOfNulls<FileAck>(3).also { for (e in entries) it[e.code.toInt()] = e }. This is an O(1) lookup with no allocation. Correct the suggestion to add bounds-checking on the byte value before indexing.

#### 🟡 LOW · writeFileAck flushes the output channel after every single-byte file ACK

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/JetzyProtocol.kt:208` · _Resource leaks / synchronization_
- **Why it's slow:** writeFileAck at lines 206-208 writes one byte and immediately flushes. This is called once per file at line 636. The flush ensures the sender receives the ACK without waiting for a buffer to fill — this is actually necessary for correctness in the ping-pong file protocol: without it, the ACK byte sits in the Ktor write buffer and the sender stalls waiting for the ACK. The 'one syscall per 1-byte write' concern is real but the flush is not optional here.
- **Fix:** A marginal improvement would be to batch the CRC int and the ACK byte into a single 5-byte write-and-flush, reducing the round-trip to one atomic frame. However, combining the CRC (written by the sender) with the ACK (written by the receiver) is not possible — they flow in opposite directions. The receiver could write both CRC-confirm and ACK in one flush, but the current protocol already flushes the CRC separately (line 438 in the send path). This is a minor optimization at most.

#### 🟡 LOW · writeString allocates a new ByteArray for every string field in every protocol message

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/JetzyProtocol.kt:228` · _Allocations / GC churn_
- **Why it's slow:** writeString at line 228 calls s.encodeToByteArray() allocating a fresh byte[] per string field. writeManifest (lines 119-133) calls writeString 4 + 5*N times (4 header strings plus 5 per manifest entry). This is a one-time cost at handshake/manifest time — it is not in the chunk loop. For a manifest with 100 files that is 504 allocations, each the size of the UTF-8 encoded field. On modern GC this is genuinely negligible.
- **Fix:** For code cleanliness a reusable encode buffer could be used, but there is no measurable performance gain here given this executes once per session. Prioritize fixing the per-chunk hot-path issues instead.

#### 🟡 LOW · readString allocates a new ByteArray per string field on every protocol message read

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/JetzyProtocol.kt:239` · _Allocations / GC churn_
- **Why it's slow:** readString at line 239 allocates val buf = ByteArray(len) for every string decoded. readManifest (lines 144-157) calls readString 3 + 4*N times per manifest read. This is a one-time cost at session startup. With 100 files that is 403 ByteArray allocations whose total size equals the UTF-8 byte length of all filenames/paths, typically kilobytes. The high-water spike is real but happens once.
- **Fix:** A growable shared decode buffer reused across calls would reduce allocations here, but the impact is too small to matter in practice. Focus on per-chunk hot-path fixes first.

#### 🟡 LOW · diag() allocates a new String and copies the entire diagnostic list on every call

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:280` · _Allocation / GC churn in hot path_
- **Why it's slow:** diag() at lines 280-284 does allocate a stamped String and copies the diagnostics list on every call. However, reviewing the inner transfer loops (lines 409-432 for send and 603-626 for receive), diag() is NOT called per-chunk — it is called only at file-level boundaries (before/after each file) and on errors. The claim of 'once per chunk' is incorrect. The actual call rate is O(number of files), which is low.
- **Fix:** No performance change needed given the actual call frequency. If per-chunk diagnostic logging is added in the future, use a Channel<String> or ring buffer to avoid StateFlow pressure. The current per-file rate is acceptable.

#### 🟡 LOW · diag() allocates two List copies plus an NSDate on every call, but is NOT called per-chunk

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:280` · _Allocations / GC churn in hot path_
- **Why it's slow:** diag() at lines 280–284 calls generateTimestampMillis() (NSDate on iOS) and executes (diagnostics.value + stamped).takeLast(50) which allocates a list of up to 51 elements then a second copy via takeLast(). The candidate finding claims diag() is called per-chunk — this is WRONG. Inspecting the actual call sites in sendFiles and receiveFiles: all diag() calls in the hot loops are per-file (e.g. lines 386, 447, 567, 676), not per-chunk. The innermost chunk loops at lines 409–432 and 603–627 do NOT contain diag() calls. The real frequency is O(files), not O(chunks), making this a low-severity concern.
- **Fix:** Use an ArrayDeque<String> as the backing store with add-and-evict logic, snapshotting to List only when emitting to the StateFlow. This avoids the two per-call List copies. But given the corrected call rate (per-file, not per-chunk), this is a minor cleanup rather than a hot-path fix.

#### 🟡 LOW · diag() allocates two intermediate List objects and copies the diagnostics list on every call

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:280` · _GC churn_
- **Why it's slow:** Each diag() call at line 282 executes (diagnostics.value + stamped).takeLast(50), producing a concatenated intermediate List and a potential second slice List, regardless of whether the diagnostics StateFlow has any active collector. diag() is called per-file and on every error/stall, not per-chunk, so the cost is bounded and not on the hottest path. Severity is low, not medium as originally claimed.
- **Fix:** Keep the log in an ArrayDeque<String>(50) and snapshot it to a List only when emitting, or check subscriptionCount before building the list. For the per-chunk concern, note that diag() is not called inside the chunk loops — only on file-level events — so this is a non-urgent cleanup.

#### 🟡 LOW · diag() rebuilds and copies the diagnostics list on every call via + and takeLast

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:282` · _Allocations / GC churn_
- **Why it's slow:** diag() at line 282-283 allocates two new lists on every call: (diagnostics.value + stamped) produces a new list of up to 51 strings, then takeLast(50) produces a second sublist. diag() is called a handful of times per file (start, finish, errors) and at handshake — not per chunk. The absolute cost is negligible per call, and the list is capped at 50 short strings. The original claim of 'at least once per file' is correct but the aggregate cost is trivially small.
- **Fix:** Use an ArrayDeque<String> capped at 50 entries with addLast/removeFirst, mutate in place, and snapshot to a list only when publishing to the StateFlow. This is cleaner but only meaningful for code clarity, not measurable performance.

#### 🟡 LOW · diag() allocates a new list on every call via list concatenation + takeLast

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:282` · _Allocations / GC churn in hot path_
- **Why it's slow:** diag() creates a new String, builds a new List via diagnostics.value + stamped (copies up to 50 elements), and calls takeLast(50) creating yet another list slice. However, review of all call sites shows diag() is called at file-level events (per-file start/end, handshake, errors), NOT inside the per-chunk tight loops. Lines 386, 447, 455, 567, 639, 676 are all at file boundaries — there is at most one diag() call per file, not per chunk.
- **Fix:** The allocation pattern is still wasteful for large file counts. Back diagnostics with a fixed-size ArrayDeque<String>(50) and replace the list + takeLast with addLast/removeFirst. The performance impact is low but the fix is straightforward.

#### 🟡 LOW · diagnostics StateFlow: list concatenation allocates on every diag() call without checking for collectors

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:282` · _GC churn_
- **Why it's slow:** This finding is a restatement of Finding 3 targeting the same line 282. The + and takeLast(50) pattern is confirmed real but the cost is negligible on the non-per-chunk event frequency. No subscriptionCount guard exists before building the list.
- **Fix:** Same as Finding 3: use an ArrayDeque internally and snapshot to List only on emit, or guard with subscriptionCount > 0.

#### 🟡 LOW · buf.size.toLong() in the chunk loop converts a constant Int to Long on every iteration

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:410` · _Allocations / GC churn_
- **Why it's slow:** Line 410 (send loop) calls buf.size.toLong() every chunk iteration. buf is allocated once before the loop (line 389) as ByteArray(bufferSize) where bufferSize is a constant. The same pattern appears at line 604 in the receive loop. buf.size is a stable Int property; .toLong() is a trivial primitive widening — the JVM/native compiler may well hoist it, but it is not guaranteed. The absolute cost is negligible: one primitive widening per ~512 KB of data.
- **Fix:** Hoist val bufSizeLong = buf.size.toLong() before each while loop and reference bufSizeLong inside. This is a micro-optimization with near-zero measurable impact but costs nothing to apply.

#### 🟡 LOW · ReceiverFileState object allocated per chunk in receive hot loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:615` · _Allocation / GC churn in hot path_
- **Why it's slow:** `ReceiverFileState(tempPath, fileRead, entry.sizeBytes)` is allocated inside the per-chunk receive loop at line 615. `tempPath` and `sizeBytes` are invariant across loop iterations; only `bytesWritten` changes. However, `ReceiverFileState` is a tiny data class (3 fields, ~40 bytes on JVM), and at 512 KB chunks the allocation rate is ~0.5–25 objects/second depending on transport speed. Modern Android GC (ART with concurrent collection) handles this allocation rate trivially without pauses. The finding is technically correct but the object size and allocation rate are too small to produce measurable GC pressure.
- **Fix:** Store resume bytes as a `LongArray` indexed by file index alongside a separate `Array<Path?>` for temp paths, eliminating the per-chunk object allocation. This is a worthwhile cleanup if `updateAt` (finding #12) is also fixed, since the two changes together reduce per-chunk heap traffic significantly.

#### 🟡 LOW · markAllNonDoneAsFailed() copies the entire fileEntries list via map() regardless of how many entries need updating

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:815` · _Unnecessary allocation_
- **Why it's slow:** markAllNonDoneAsFailed() at lines 815-821 calls fileEntries.value.map { ... } which always creates a full ArrayList copy of all entries. It is called only on error paths (not the transfer hot path), so the cost is incurred at most once per failed transfer. The allocation is wasteful but inconsequential.
- **Fix:** Early-return if none of the entries are Active or Pending. This avoids the copy for the common case of a completed transfer that then hits a protocol error during clean-close.

#### 🟡 LOW · markAllNonDoneAsFailed rebuilds the entire fileEntries list via .map{} on failure/stall

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:816` · _Algorithmic inefficiency_
- **Why it's slow:** markAllNonDoneAsFailed at lines 815-821 calls fileEntries.value.map { ... } which allocates a new list copying all N entries, then assigns to the StateFlow. This is called only from failure and stall paths (lines 351, 358, 470, 541, 688, 803) — never from the hot chunk loop. The allocation is a one-time event on an already-failed transfer. The claim is accurate but the path is genuinely cold.
- **Fix:** For code clarity, replace with a targeted update that only changes Active/Pending entries. However the performance impact is negligible — this runs at most once per session on a failure path.

#### 🟡 LOW · String concatenation in loop when building folder destination path

- **Where:** `shared/src/commonMain/kotlin/jetzy/managers/P2PManager.kt:851` · _Allocation / GC_
- **Why it's slow:** finalizeReceivedFilesAt at lines 850–856 builds the destination path by concatenating strings inside a for loop: `currentPath = "$currentPath/$dir"`. Each iteration allocates a new String. For a d-level deep directory this is O(d^2) in total characters. However this code runs once at save time (cold path), not during transfer, and directory depths are typically single digits.
- **Fix:** Use a StringBuilder or `dirs.joinToString("/", prefix = destDir.path + "/")` to build the full path in a single pass before constructing any Path objects.

### Transport · Mediums — 51 (8H / 20M / 23L)

#### 🔴 HIGH · Busy-wait polling loop in connectToPeer (Wi-Fi Direct)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WiFiDirectP2PM.kt:241` · _Busy-wait / tight polling loop_
- **Why it's slow:** Inside `connectToPeer`, after a successful connect request the code spins in `while (connection == null) delay(500.milliseconds)` on the calling coroutine for up to 25 seconds. `connection` is a plain `var` field on `P2PManager` with no `@Volatile` annotation, yet it is written by the broadcast-receiver thread inside `handleP2pConnection` and read here on a coroutine thread — a genuine cross-thread visibility race on top of the polling waste. Each iteration wakes the scheduler, rechecks the field, and goes back to sleep.
- **Fix:** Replace the polling loop with a `CompletableDeferred<Connection>` (or a `Channel`) that `handleP2pConnection` completes once the socket is ready, eliminating all intermediate wakeups and the visibility race. The `connection` field should also be annotated `@Volatile` or moved behind a mutex.

#### 🔴 HIGH · Spin-idle loop when readAvailable returns 0 (Wi-Fi Aware write pump)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:384` · _Busy-wait / tight polling loop_
- **Why it's slow:** The write pump calls `write.readAvailable(buf)` and, when it returns `<= 0` and the channel is not yet closed, immediately `continue`s without suspending. `ByteChannel.readAvailable` is non-suspending and returns 0 when no bytes are immediately ready. The loop therefore spins at full coroutine speed burning a CPU core whenever the protocol layer momentarily has nothing to write.
- **Fix:** Replace `readAvailable` with the suspending `write.read(buf)` / `write.readFully` variants, or call `write.awaitContent()` before `readAvailable`, so the coroutine suspends rather than spins while the channel is empty.

#### 🔴 HIGH · Unbuffered flush on every chunk in the write pump (Wi-Fi Aware bridge)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:389` · _Unbuffered / inefficient stream IO_
- **Why it's slow:** The write pump at line 389 calls `output.flush()` after every `output.write(buf, 0, n)`, and the read pump at line 369 calls `read.flush()` after every `read.writeFully(buf, 0, n)`. With `bufferSize = 512 KB`, each chunk triggers a kernel `send()` immediately, preventing TCP from coalescing segments. On a fast NAN link these per-chunk syscall round-trips are the dominant overhead.
- **Fix:** Remove the per-chunk `flush()` calls from both pump loops. The `write.close()` / `read.close()` in the `finally` blocks already drain any buffered bytes on teardown. If latency matters, flush at most once per 100 ms using a throttle variable.

#### 🔴 HIGH · MC delegate callbacks mutate shared state from the MC internal queue without a coroutine hop (MpcP2PM)

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/MpcP2PM.kt:66` · _Blocking or heavy work on wrong dispatcher_
- **Why it's slow:** The sessionDelegate, browserDelegate, and advertiserDelegate lambdas run directly on MCSession's/MCNearbyServiceBrowser's internal ObjC queues. The lambda bodies write to peerIdMap (a plain unsynchronized HashMap), connectedPeer, mpcInputStream, mpcOutputStream, and alreadyBridged (all non-@Volatile vars). Concurrently, connectToPeer() reads peerIdMap[peer.id] on a coroutine thread. This is a data race: a write to a non-thread-safe HashMap on the browser queue racing with a read on the coroutine thread is undefined behavior on Kotlin/Native. Additionally, alreadyBridged is read and written without @Volatile, so a write on one thread may not be visible to another, allowing tryBridgeStreams to double-bridge on a race between onPeerStateChanged and onStreamReceived if they ever arrive on different queues.
- **Fix:** Wrap each lambda body in p2pScope.launch(PreferablyIO) { ... } so all state mutations happen on a single coroutine dispatcher. Replace peerIdMap with a ConcurrentHashMap or confine all accesses to the coroutine dispatcher. Mark alreadyBridged as @Volatile or replace the double-check with an AtomicBoolean.

#### 🔴 HIGH · MCSession delegate not cleared on cleanup — retain cycle prevents MCSession dealloc (MpcP2PM)

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/MpcP2PM.kt:296` · _Resource leak degrading performance over time_
- **Why it's slow:** cleanup() calls session.disconnect() but never sets session.delegate = null. MCSession retains its delegate object with a strong ObjC reference. JetzyMCSessionDelegate holds var properties (onPeerStateChanged, onStreamReceived, etc.) that contain Kotlin lambda closures capturing the MpcP2PM instance (via diag, openOutputStreamTo, tryBridgeStreams, etc.). This forms a reference cycle: MCSession → sessionDelegate → MpcP2PM (via closure captures) → session. Because MpcP2PM() is instantiated fresh per-connection (confirmed in main.kt lines 54 and 75), each disconnected session leaks: the MCSession and all its captured state remain live until the sessionDelegate reference cycle is broken. Over multiple reconnect attempts (e.g. failed and retried transfers), this accumulates unreleased MCSession objects.
- **Fix:** Add session.delegate = null at the start of cleanup() before disconnect(). Also null out the lambda callbacks: sessionDelegate.onPeerStateChanged = null, sessionDelegate.onStreamReceived = null, advertiserDelegate.onInvitationReceived = null, etc. This breaks the closure capture chain and allows both the delegate and MpcP2PM to be collected.

#### 🔴 HIGH · BT write-pump coroutine busy-spins when readAvailable returns 0 — 100% CPU on IO thread

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/BluetoothSppP2PM.desktop.kt:254` · _Busy-wait / tight polling loops_
- **Why it's slow:** The write-pump coroutine at lines 254-261 calls write.readAvailable(buf) and when it returns n <= 0, immediately continues the while loop with no delay or yield. Ktor's ByteChannel.readAvailable() can legitimately return 0 when no data is currently buffered without suspending. This creates a tight busy-spin on the IO dispatcher thread that consumes 100% of that thread's CPU until data arrives or the channel closes. This is an active bug whenever the RFCOMM write channel is idle between transfers.
- **Fix:** Replace readAvailable with write.readFully(buf) (which suspends until data is available) or use the await()-based Ktor Channel API. At minimum, insert `yield()` in the n <= 0 branch, but the correct fix is to use a truly suspending read call.

#### 🔴 HIGH · startWindowsPeerPolling: PowerShell process spawned every 3 seconds with potential pipe deadlock

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/WiFiDirectP2PM.desktop.kt:155` · _Resource leaks / repeated expensive lookups_
- **Why it's slow:** The Windows peer-polling loop forks a new powershell.exe process every 3 seconds (line 155-169) for the lifetime of discovery. The script itself is built once before the loop (correct, no trimIndent per-iteration), but each iteration spawns a new powershell.exe which loads WinRT type projections from scratch. runCommand's deadlock risk (waitFor before stdout drain) also applies here. Over a 60-second discovery window this is 20 powershell.exe forks with 8-second timeout each. The WinRT FindAllAsync is re-triggered from scratch rather than using a DeviceWatcher for incremental events.
- **Fix:** Use a persistent powershell.exe process with stdin/stdout pipes to run a long-lived script that uses DeviceWatcher events and writes new devices to stdout as they arrive, avoiding repeated process creation.

#### 🔴 HIGH · Write-pump busy-spins when readAvailable returns 0 — tight CPU loop consuming a full IO thread

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:154` · _Busy-wait / tight polling loop_
- **Why it's slow:** In the write pump (lines 154–162), when writeChannel.readAvailable(buf) returns 0 the code immediately continues the while loop without yielding or suspending. Ktor's ByteReadChannel.readAvailable() is non-blocking and returns 0 when no data is buffered, so this loop busy-spins at full CPU speed during any gap between chunks. The identical pattern exists in MpcP2PM.kt at lines 199–204. This keeps an IO-pool thread at 100% CPU between chunks.
- **Fix:** Replace readAvailable with a suspending read: use awaitContent() before readAvailable, or use writeChannel.read { ... } which suspends when empty. A minimal fix is to add yield() in the n<=0 branch to cooperatively release the thread, but the correct fix is to use a suspending read primitive so the coroutine parks until data arrives.

#### 🟠 MED · Full peer list rebuilt (O(n)) on every Bluetooth device discovery event

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/BluetoothSppP2PM.kt:171` · _Algorithmic inefficiency / GC churn in repeated path_
- **Why it's slow:** Every call to `registerDevice` (triggered once per bonded device at startup and once per discovered device during scanning) rebuilds the full `availablePeers` list by calling `foundDevices.values.map { it.name ... }`. Each `it.name` call on a `BluetoothDevice` issues a Binder IPC to the Bluetooth daemon — not a local field read. For N bonded devices, startup alone costs 1+2+...+N = O(N²) Binder IPC calls total. During active discovery, each new device adds another full scan of all previously seen devices. The code also computes `nameSafe` for the new device separately (line 169) but then re-fetches it again inside the map lambda (line 172) for the same device, doubling one Binder call unnecessarily.
- **Fix:** Store device names at insertion time: change `foundDevices` from `Map<String, BluetoothDevice>` to `Map<String, P2pPeer>`. In `registerDevice`, create one `P2pPeer` for the new device and update `foundDevices[id] = newPeer`, then publish `availablePeers.value = foundDevices.values.toList()`. This reduces each discovery event to O(1) Binder IPC calls and O(N) list copy.

#### 🟠 MED · Two 512 KB ByteArrays allocated per connected socket rather than sharing one buffer

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/BluetoothSppP2PM.kt:244` · _Allocation / GC pressure_
- **Why it's slow:** Each `bridgeBluetoothSocket` call allocates two independent 512 KB `ByteArray`s (one per pump coroutine at lines 244 and 261). These both inherit `bufferSize = 512 * 1024` from the parent `P2PManager` — a value tuned for fast Wi-Fi transports. At Bluetooth SPP's ~2 Mbps practical throughput (~250 KB/s), the OS delivers data in RFCOMM packet-sized chunks (typically 990–4096 bytes), so neither buffer is ever filled in a single `InputStream.read()` call. The 512 KB allocation wastes 1 MB of heap per connection for a transport that would be equally efficient with a 16–64 KB buffer.
- **Fix:** Override `bufferSize` in `BluetoothSppP2PM` (make the field `open` in `P2PManager`) and set it to `64 * 1024`. This eliminates 896 KB of wasted heap per BT connection with no throughput regression since RFCOMM packet MTU is ~4 KB at most.

#### 🟠 MED · permissionRequirements is a get() property that rebuilds the full list on every recomposition

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/HotspotP2PM.kt:45` · _Redundant recomputation / allocation in repeated path_
- **Why it's slow:** `permissionRequirements` is an `override val ... get() { return buildList { ... } }` that calls up to 6 builder functions each time, each allocating a new `PermissionRequirement` with closures. AdamScreen (line 228) reads `p.manager.permissionRequirements` directly in the composable body without `remember`, so it is evaluated on every recomposition. The resulting fresh list is passed to `PermissionGateDialog`, which recomputes `structuralKey` each time and the `produceState` producer calls `requirements.map { it.isGrantedNow() }` every 400ms. Per-recomposition list construction is the waste — not the poll tick itself.
- **Fix:** Cache the list as a lazy `val` computed once at construction time. The `PermissionRequirement` objects are stateless descriptors; only their `isGrantedNow` lambdas need to remain live, and those already close over stable references. Then wrap the `p.manager.permissionRequirements` read in `remember(p.manager)` at the AdamScreen call site.

#### 🟠 MED · Full list copy + allocation on every requestPeers callback (Wi-Fi Direct BroadcastReceiver)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WiFiDirectP2PM.kt:171` · _Allocations / GC churn in repeated paths_
- **Why it's slow:** The `WIFI_P2P_PEERS_CHANGED_ACTION` handler (lines 170-178) maps the full device list to new `P2pPeer` objects and writes a new list to `availablePeers` unconditionally on every peer-list update, which can fire every few seconds during discovery. There is no diff check against the existing `availablePeers.value`.
- **Fix:** Before emitting, compare the incoming device addresses against `availablePeers.value` by address. Only emit a new list if the set of addresses or their statuses actually changed, avoiding spurious recompositions and pointless allocations on stable scans.

#### 🟠 MED · Redundant new SelectorManager per-connection attempt (Wi-Fi Direct handleP2pConnection)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WiFiDirectP2PM.kt:276` · _Resource leaks that degrade performance over time_
- **Why it's slow:** `handleP2pConnection` at line 276 creates a `SelectorManager(PreferablyIO)` as a local variable on every call. `WiFiDirectP2PM.cleanup()` does not close any selector (there is no class-level `selectorManager` field). On each `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast with `isConnected == true` a new selector thread is spawned and the previous one is leaked. Over multiple group formation/dissolution cycles — which are common during retry sequences — each unclosed SelectorManager holds a live NIO selector thread and its file descriptor.
- **Fix:** Promote `selectorManager` to a class-level field (as `HotspotP2PM` does at line 43), initialize it lazily once, and close it in `cleanup()`. Guard `handleP2pConnection` with an early return if the selector is already managing a live connection.

#### 🟠 MED · SelectorManager in WiFiDirect client path leaks on repeated TCP connect failures

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WiFiDirectP2PM.kt:290` · _Resource leaks that degrade performance over time_
- **Why it's slow:** In `handleP2pConnection`, `selectorManager` is a local variable created at line 276 and is never closed regardless of success or failure. On all 5 TCP connection attempts failing, the selectorManager (and its NIO selector thread and file descriptor) is abandoned. Because `handleP2pConnection` can be called again on the next `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast, each failed group formation cycle leaks one more selector thread. This is the same root cause as finding #7 (local SelectorManager) viewed from the client retry angle.
- **Fix:** This is the same fix as the SelectorManager-per-call finding: promote `selectorManager` to a class-level field, close it in `cleanup()`, and create it once at initialization or first use.

#### 🟠 MED · Eager full-list copy on every peer list update (Wi-Fi Aware onServiceDiscovered)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:213` · _Allocations / GC churn in repeated paths_
- **Why it's slow:** Every `onServiceDiscovered` callback unconditionally replaces `availablePeers.value` by mapping all `peerHandles.values` to new `P2pPeer` objects. NAN re-fires this callback on every scan interval even for already-known peers, so the map and all its `P2pPeer` allocations happen repeatedly for a stable peer set. A check whether the peer and its handle are already present would allow skipping the allocation and the downstream StateFlow emission.
- **Fix:** Check if `peerHandles[peerName]` already exists with the same `PeerHandle` before updating. Only emit a new `availablePeers` list when the map actually changed (new peer added or existing handle refreshed).

#### 🟠 MED · activeNetworkCallback is overwritten without unregistering the previous one (Wi-Fi Aware)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:438` · _Resource leaks that degrade performance over time_
- **Why it's slow:** `requestNanNetwork` assigns `activeNetworkCallback = cb` at line 438 without unregistering the previous callback. While in normal operation only one side (server or client) calls `requestNanNetwork` per connection attempt (role is determined by `localRole`), a second call without an intervening `cleanup()` — such as when the user retries a connection — will leak the previous callback in `ConnectivityManager`. `cleanup()` correctly unregisters it, but `prepareForResume()` does not call `requestNanNetwork` cleanup, so a mid-transfer reconnect attempt could leak the old callback.
- **Fix:** Add `activeNetworkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }` before the `activeNetworkCallback = cb` assignment at line 438.

#### 🟠 MED · LanMdnsP2PM NSNetServiceDelegate callbacks mutate shared maps on whichever run-loop thread the browser runs on, without synchronization

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/LanMdnsP2PM.kt:65` · _Blocking or heavy work on main/UI thread_
- **Why it's slow:** NSNetServiceBrowser is created in startDiscoveryAndAdvertising() which runs on PreferablyIO (line 98 launches it via p2pScope.launch(PreferablyIO)). However, NSNetServiceBrowser schedules its callbacks on the run loop of the thread it was created on; if PreferablyIO resolves to a thread without an active run loop (common for Kotlin/Native coroutine threads), callbacks may not fire at all, or may fire on the main run loop as a fallback. Meanwhile connectToPeer at line 152 reads resolvedPeers from a different p2pScope.launch(PreferablyIO) coroutine. The plain mutableMapOf is not thread-safe. There is a genuine data race between the resolve callback writing resolvedPeers and connectToPeer reading it.
- **Fix:** Protect resolvedPeers, resolvingDelegates, and pendingResolve with a Mutex, or move all access into a single-threaded coroutine context (e.g. a dedicated Dispatchers.Main or a single-thread dispatcher). Alternatively schedule the NSNetServiceBrowser on a dedicated background NSRunLoop and serialize all map access through that thread.

#### 🟠 MED · resolvingDelegates and pendingResolve lists grow unbounded — resolved entries are never removed

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/LanMdnsP2PM.kt:72` · _Resource leaks that degrade performance over time_
- **Why it's slow:** Every service found appends to resolvingDelegates (line 72) and pendingResolve (line 74). After resolution the onResolved callback (lines 64–69) adds to resolvedPeers but never removes the delegate from resolvingDelegates or the service from pendingResolve. Both lists grow monotonically. stopDiscoveryAndAdvertising() clears pendingResolve but not resolvingDelegates; cleanup() clears both but only at session end. In a long-lived session with many peer churn events, resolvingDelegates holds strong references to all ever-created delegate objects, and pendingResolve holds NSNetService objects that were already resolved but never stopped.
- **Fix:** In the onResolved lambda, remove the completed delegate from resolvingDelegates and the service from pendingResolve immediately after resolution. Pass the delegate instance into the lambda via a local variable so it can call resolvingDelegates.remove(resolveDelegate), and remove the same service object from pendingResolve at the same time.

#### 🟠 MED · NSData→ByteArray memcpy called twice per connect: once at resolve, once in connectToPeer

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/LanMdnsP2PM.kt:257` · _Allocations / GC churn in hot path_
- **Why it's slow:** toHostString() at line 257 allocates ByteArray(length) and calls memcpy from NSData.bytes for every address blob. firstResolvableAddress() is called at resolve time (line 63) to confirm the address and at connect time (line 146) to retrieve the host string for dialling — both on the same cached NSNetService object. The resolved host string is never cached, so the NSData→ByteArray copy runs a second time on connect. For the IPv6 branch, the function also allocates an 8-element intermediate list and performs joinToString.
- **Fix:** Store the resolved host string alongside the NSNetService in resolvedPeers as a Pair<NSNetService, String> or a small data class. connectToPeer reads the pre-parsed string directly from the map, eliminating the second NSData→ByteArray copy entirely. This also fixes the IPv6 list allocation for the connect path.

#### 🟠 MED · runCommand reads stdout only AFTER process exits — potential deadlock on large output

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/BluetoothSppP2PM.desktop.kt:296` · _Allocations / GC churn_
- **Why it's slow:** runCommand calls proc.waitFor() before draining stdout (line 299-301). If the subprocess writes more than the OS pipe buffer (~64 KB on Linux), the subprocess blocks waiting for the JVM to consume output, while the JVM blocks in waitFor() waiting for the subprocess to exit — a classic pipe deadlock. The timeout path (proc.destroyForcibly()) prevents an infinite hang but the deadlock still stalls for the full timeout duration. This pattern is duplicated in WiFiDirectP2PM.runCommand (line 311). The startWindowsPeerPolling loop (WiFiDirectP2PM line 155) calls this every 3 seconds, and the PowerShell WinRT enumeration output could exceed 64 KB for a large device list.
- **Fix:** Drain subprocess output on a separate thread or coroutine concurrently with waitFor(), e.g. by launching a reader coroutine on the inputStream before calling waitFor().

#### 🟠 MED · startDiscoveryAndAdvertising creates a new SelectorManager for the server socket, also never closed

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/LanMdnsP2PM.desktop.kt:58` · _Resource leaks / repeated expensive lookups_
- **Why it's slow:** Line 58 in LanMdnsP2PM, line 89 in WiFiDirectP2PM, and line 54 in LanHostP2PM all call `aSocket(SelectorManager(PreferablyIO)).tcp().bind(...)` with an anonymous, uncaptured SelectorManager. The SelectorManager is never stored and so is never closed, leaking its NIO Selector and selector thread. If startDiscoveryAndAdvertising is called multiple times (e.g. user stops and restarts discovery), one leaked SelectorManager accumulates per cycle.
- **Fix:** Assign the SelectorManager to a field (e.g. `private var selectorManager: SelectorManager? = null`) initialized lazily and closed in cleanup(). LanP2PM already demonstrates this pattern correctly.

#### 🟠 MED · serviceListener.serviceAdded blocks the jmdns internal thread with a 1000 ms requestServiceInfo call

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/LanMdnsP2PM.desktop.kt:117` · _Blocking or heavy work on wrong dispatcher_
- **Why it's slow:** serviceAdded (line 114-118) calls event.dns.requestServiceInfo(event.type, event.name, 1000) synchronously on the jmdns internal network thread. This is a blocking mDNS round-trip with up to a 1000 ms timeout. While blocked, the jmdns thread cannot process any other mDNS packets (responses, keep-alives, or additional serviceAdded events), causing service resolution to serialize when multiple peers are discovered simultaneously.
- **Fix:** Dispatch the requestServiceInfo call to a background coroutine: `p2pScope.launch(PreferablyIO) { event.dns.requestServiceInfo(event.type, event.name, 1000) }` so the jmdns thread is released immediately.

#### 🟠 MED · connectToPeer spawns a new SelectorManager on every outbound mDNS dial — selector thread leak

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/LanMdnsP2PM.desktop.kt:154` · _Resource leaks / repeated expensive lookups_
- **Why it's slow:** connectToPeer creates `aSocket(SelectorManager(PreferablyIO))` inline (line 154) on every dial attempt. The SelectorManager is never captured or closed. Each SelectorManager creates its own NIO Selector and a selector/dispatcher thread. On repeated connect attempts (failed dial, user retries), one unreachable SelectorManager with a leaked OS selector and thread accumulates per attempt. The socket (`ktor`) is assigned to `connection` and managed, but its SelectorManager is not.
- **Fix:** Store a single SelectorManager as a class field and close it in cleanup(), following the pattern in LanP2PM's `selector()` lazy accessor (LanP2PM.desktop.kt line 35-36).

#### 🟠 MED · startLinuxPeerPolling: O(n) full List allocation and new P2pPeer objects on every 2-second poll

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/WiFiDirectP2PM.desktop.kt:122` · _Allocations / GC churn_
- **Why it's slow:** availablePeers.value = foundPeers.map { ... } at line 122 is called unconditionally every 2 seconds regardless of whether the peer set changed. The for-loop at line 114 may hit `continue` for all known peers (no new discoveries), yet the map and StateFlow assignment still run outside the for-loop. Same pattern in startWindowsPeerPolling at line 165. On a stable network with no new peers, this allocates a new ArrayList and P2pPeer per known peer every 2 seconds indefinitely.
- **Fix:** Move the availablePeers.value assignment inside the `if (addr in foundPeers) continue` branch (i.e. only update when a genuinely new peer is added). Track a dirty flag or compare map size to avoid the assignment when nothing changed.

#### 🟠 MED · Fixed 2-second delay after Wi-Fi join used for synchronization — should use active IP verification instead

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/LanWifiP2PM.kt:101` · _Busy-wait / tight polling loops; fixed delay used for synchronization_
- **Why it's slow:** After joinWithRetry succeeds at line 100, connectAttempt unconditionally suspends for 2 seconds (line 101) 'for the network to settle'. The join was already confirmed via verifyJoinedExpectedSsid which polled until the SSID matched, meaning association is already confirmed. The 2-second dead time adds latency on every successful join. If DHCP has not finished in 2 seconds, the TCP connect will fail and retry via tcpConnectWithTimeout anyway. This is a latency issue, not a busy-wait — delay() is a proper coroutine suspend, not CPU spin.
- **Fix:** Remove the fixed delay and rely on tcpConnectWithTimeout's retry budget (5 attempts × 2s back-off) to cover the DHCP settling window. Alternatively replace with an active poll using getifaddrs() to confirm the interface has a routable IP before attempting TCP, which eliminates dead time on fast-DHCP networks while remaining robust on slow ones.

#### 🟠 MED · WifiAwareBridge onConnected callback opens streams and launches coroutines synchronously on the main queue

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:61` · _Blocking or heavy work on main/UI thread_
- **Why it's slow:** WifiAwareBridge.kt documents that all callbacks fire on the main queue. The onConnected callback (lines 61–65) calls bridgeStreamsToChannels synchronously, which calls input.open() and output.open() on NSInput/OutputStream before returning — these can briefly block for resource allocation. isHandshaking.value = true at line 63 emits a StateFlow from the main queue. The two ByteChannel objects and the coroutine launches inside bridgeStreamsToChannels (lines 133, 150) are dispatched to PreferablyIO correctly, so the actual I/O pumps do not run on main. The main-thread exposure is the open() calls and object construction, not sustained I/O.
- **Fix:** Dispatch the entire body of onConnected into p2pScope.launch(PreferablyIO) { ... } so stream opens happen off the main queue. The StateFlow assignments (isHandshaking, connectionReady) are thread-safe as-is and do not need to stay on main.

#### 🟠 MED · Per-chunk flush of internal ByteChannel on every read-pump iteration adds coroutine scheduling overhead

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:141` · _Allocations / GC churn in hot path_
- **Why it's slow:** In the read pump (lines 136–147), readChannel.flush() is called after every writeFully() for each 512 KB chunk read from NSInputStream. Ktor's ByteChannel.flush() signals the consumer coroutine, adding coroutine scheduling overhead on every chunk. While flushing ensures the consumer can proceed promptly, doing it every 512 KB is more frequent than necessary — Ktor's internal buffering will trigger reads without explicit flush for large transfers. The identical pattern exists in MpcP2PM.kt at line 185.
- **Fix:** Flush less frequently — for example, only when readAtMostTo returns less than the full buffer size (indicating the stream paused), or rely on Ktor's internal buffer management to wake the consumer. The consumer uses readFully() which will block until data is available; explicit per-chunk flush is not required for correctness at 512 KB granularity.

#### 🟠 MED · Per-chunk sink.flush() on every write-pump iteration serialises OS write syscalls unnecessarily

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:161` · _Allocations / GC churn in hot path_
- **Why it's slow:** The write pump calls sink.flush() after every 512 KB chunk write to the buffered NSOutputStream sink (line 161). The kotlinx-io buffered sink holds an internal write buffer; flush forces it to drain to the OS, preventing coalescing of back-to-back writes and potentially stalling the coroutine while NSOutputStream drains. The identical pattern exists in MpcP2PM.kt at line 206.
- **Fix:** Remove the per-chunk flush and let the buffered sink coalesce writes internally. Only flush explicitly on close (which already happens via sink.close() in the finally block) or when low-latency framing requires it. This is especially impactful for the last small chunk of each file where flush overhead can dominate the write cost.

#### 🟡 LOW · Polling hotspot IP with System.currentTimeMillis() in a tight delay loop (Hotspot)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/HotspotP2PM.kt:226` · _Busy-wait / tight polling loop_
- **Why it's slow:** `waitForHotspotIpAddress` polls `getHotspotIpAddress()` every 250 ms for up to 3 seconds (12 iterations at most). Each call enumerates network interfaces via a JNI/syscall. The 250 ms suspension between calls means this is not a tight spin — the coroutine suspends properly between iterations. The cost is 12 interface-enumeration syscalls on a cold startup path.
- **Fix:** This is acceptable given the 250 ms delay. The only real optimization is to break out of `getHotspotIpAddress` as soon as the first Inet4Address match is found (the current code already does this via early `return`). No change needed on the polling structure.

#### 🟡 LOW · getHotspotIpAddress allocates String via hostAddress on every interface address (Hotspot)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/HotspotP2PM.kt:285` · _Allocations / GC churn in repeated paths_
- **Why it's slow:** `getHotspotIpAddress` calls `address.hostAddress` (line 285) before the `address is Inet4Address` check (line 286). On Android, `hostAddress` does not trigger DNS — it returns the textual IP form directly — but it does allocate a new `String` for every address on every interface, including IPv6 addresses that the subsequent `Inet4Address` check immediately discards. The function is called up to 12 times from the polling loop.
- **Fix:** Reorder: cast to `Inet4Address` first (`if (address is Inet4Address && !address.isLoopbackAddress)`), then call `address.hostAddress` only once for the match. This avoids allocating strings for every non-matching IPv6 address.

#### 🟡 LOW · availablePeers list rebuilt on every mDNS onServiceLost / onServiceResolved event

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/LanMdnsP2PM.kt:146` · _Allocation / GC churn in repeated path_
- **Why it's slow:** Both `onServiceResolved` and `onServiceLost` call `foundPeers.values.map { P2pPeer(id = it.serviceName, name = it.serviceName, signalStrength = 3) }`, rebuilding the full peer list on each event. Unlike the Bluetooth case, `NsdServiceInfo.serviceName` is a plain `String` field (no Binder IPC). The map + StateFlow publish is cheap (typically <10 peers on a local network). This is a real but very minor allocation, and the pattern is less harmful than the Bluetooth equivalent because there is no repeated Binder IPC per entry.
- **Fix:** Maintain a `Map<String, P2pPeer>` in parallel with `foundPeers` so the mapping only happens once at resolve time (O(1) per event), and publish `peersMap.values.toList()`. This eliminates the per-event map allocation, though the practical benefit is negligible for typical local network peer counts.

#### 🟡 LOW · Fixed 250 ms delay before client socket connect with no backoff (Wi-Fi Aware)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:337` · _Busy-wait / tight polling loop_
- **Why it's slow:** A hardcoded `delay(250)` (milliseconds) is inserted before the client TCP connect to work around chipsets that briefly signal the NAN link as up before routing is ready. If routing takes longer than 250 ms, the single `network.socketFactory.createSocket` call fails and the entire connection attempt is aborted with no retry at this level. There is no backoff or retry loop.
- **Fix:** Replace the fixed pre-dial delay with a short retry loop (e.g. 3 attempts at 250/500/1000 ms) catching `java.net.ConnectException` specifically, so transient routing delays are healed automatically rather than failing the full handshake.

#### 🟡 LOW · getCapabilities() IPC call inside NetworkCallback on LinkProperties change (Wi-Fi Aware)

- **Where:** `shared/src/androidMain/kotlin/jetzy/managers/WifiAwareP2PM.kt:426` · _Synchronous network/disk on hot callback path_
- **Why it's slow:** `onLinkPropertiesChanged` calls `connectivityManager.getNetworkCapabilities(network)` which performs a synchronous Binder IPC to the connectivity service. The callback fires only once per connection attempt (gated by `if (!gate.isCompleted)`), so this is a cold-path call, not a repeated hot-path call. The finding's thread claim is also incorrect: `requestNetwork` is called without a `Handler` argument, so Android dispatches callbacks on a connectivity framework thread, not the main thread. The IPC is still wasteful because the `NetworkCapabilities` object containing `WifiAwareNetworkInfo` (including `peerIpv6Addr`) is already delivered to `onCapabilitiesChanged`, making the extra IPC call redundant.
- **Fix:** Override `onCapabilitiesChanged` and cache `peerIpv6Addr` from the delivered `NetworkCapabilities` object. In `onLinkPropertiesChanged`, use the cached value instead of issuing a new `getNetworkCapabilities` IPC call.

#### 🟡 LOW · availablePeers.value rebuilt as a new List on every peer found/lost event with a full map traversal

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/LanMdnsP2PM.kt:66` · _Allocations / GC churn in hot path_
- **Why it's slow:** Both the onServiceFound resolve callback (line 66) and onServiceLost callback (line 80) rebuild availablePeers.value via resolvedPeers.values.map { P2pPeer(...) }, traversing all known peers and allocating a new List<P2pPeer> plus new P2pPeer objects for every peer on every event. The same pattern appears in WifiAwareP2PM.kt at lines 51 and 57 using foundPeers.values.toList(). Discovery events are infrequent in normal use so this is not a hot path; impact is bounded by peer count.
- **Fix:** Maintain a parallel Map<String, P2pPeer> updated incrementally — add or remove only the changed peer, then emit peerMap.values.toList(). This avoids recreating P2pPeer objects for unchanged peers. For WifiAwareP2PM the toList() copy is unavoidable for StateFlow thread-safety but P2pPeer reuse would eliminate object churn.

#### 🟡 LOW · IPv6 address parse allocates an 8-element List<String> and calls joinToString on every toHostString invocation

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/LanMdnsP2PM.kt:273` · _Allocations / GC churn in hot path_
- **Why it's slow:** The AF_INET6 branch at lines 273–278 builds an 8-element list via (0 until 8).map { ... } then immediately calls joinToString(":"), allocating the list, 8 short-lived String objects for hex parts, plus the join result String. This occurs on every address blob in the IPv6 path inside firstResolvableAddress(). The path is cold (discovery events only, not per-chunk), and IPv4 is preferred so IPv6 is a fallback — impact is minimal but the allocation is gratuitous.
- **Fix:** Use a StringBuilder with repeated append calls to build the colon-separated hex string, avoiding the intermediate list and 8 short-lived String objects. A simple loop with StringBuilder.append handles the same logic with a single final String allocation.

#### 🟡 LOW · Full peer list rebuild allocated on every peer discovery or loss event (MpcP2PM)

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/MpcP2PM.kt:100` · _Allocations / GC churn in hot path_
- **Why it's slow:** onPeerFound rebuilds the entire List<P2pPeer> from peerIdMap.values via .map{} on every discovery event, allocating a new P2pPeer for every currently-known peer. onPeerLost uses filter{} to build a new list scanning all entries. MPC's practical peer limit is around 8 peers per MCSession, so the O(n) scan allocates at most 8 objects per event. In a normal Jetzy session this fires a handful of times during discovery, not repeatedly. The allocation pressure is real but entirely negligible at this scale.
- **Fix:** For correctness, the peerIdMap race with connectToPeer (finding above) should be fixed first. After that, consider a MutableStateFlow<List<P2pPeer>> updated via StateFlow.update{ list -> list + newPeer } to avoid the full .map{} rebuild, but this is cosmetic given the bounded peer count.

#### 🟡 LOW · Two new ByteArray(512 KiB) allocations every time tryBridgeStreams() is called (MpcP2PM)

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/MpcP2PM.kt:179` · _Allocations / GC churn in hot path_
- **Why it's slow:** tryBridgeStreams() allocates two 512 KB ByteArrays (one for the read pump coroutine, one for the write pump coroutine) each time it is called. teardownStreams() resets alreadyBridged = false, so a reconnect triggers fresh allocations. The 1 MB total is real but occurs only once per connection attempt, not in the transfer hot loop. The Kotlin/Native GC can collect the old arrays after teardown. The suggestion to hoist these to class-level fields is actually dangerous in this design: each pump coroutine owns its buffer exclusively, and making them shared class fields would require synchronization on reconnect.
- **Fix:** Accept the per-connection allocation. If reconnects become frequent (e.g. flaky hardware), consider a simple object pool with AtomicReference slots, but this is premature optimization for a once-per-connection cost.

#### 🟡 LOW · sink.flush() inside the write pump on every chunk (MpcP2PM)

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/MpcP2PM.kt:206` · _Allocations / GC churn in hot path_
- **Why it's slow:** The buffered NSOutputStream sink's flush() is called after each readAvailable() result. Because readAvailable suspends until data arrives and returns up to 512 KB at a time, flush() is called at most once per 512 KB chunk — roughly once per I/O round trip. The kotlinx-io BufferedSink will have already emitted most of the data to NSOutputStream during the write() call (its internal buffer is 8 KB), so flush() is primarily a safeguard. The overhead is one extra method call per chunk across the ObjC boundary; at 250 Mbps this is around 60 calls/sec, which is negligible compared to the I/O cost.
- **Fix:** The flush is low-risk to remove in practice since each 512 KB write already drains the buffered sink's 8 KB internal buffer. However, removing it risks delaying the last partial chunk at end-of-stream. If removed, ensure sink.close() in the finally block is sufficient to flush remaining data.

#### 🟡 LOW · JetzyMCSessionDelegate and JetzyMCAdvertiserDelegate declared public instead of private (MpcP2PM)

- **Where:** `shared/src/appleMain/kotlin/jetzy/managers/MpcP2PM.kt:309` · _Resource leak degrading performance over time_
- **Why it's slow:** JetzyMCSessionDelegate (line 309) and JetzyMCAdvertiserDelegate (line 335) have default (public) visibility while JetzyMCBrowserDelegate (line 349) is correctly private. The performance overhead claim — that ObjC runtime class registration is more expensive for public Kotlin/Native NSObject subclasses — is not accurate: all Kotlin/Native classes inheriting from NSObject are registered in the ObjC class table regardless of their Kotlin visibility modifier. The real risk is accidental misuse: any code in the module can instantiate these delegates and assign them to an unrelated MCSession, causing callbacks to fire into an unexpected MpcP2PM instance.
- **Fix:** Mark both JetzyMCSessionDelegate and JetzyMCAdvertiserDelegate as private (matching JetzyMCBrowserDelegate) to prevent unintended instantiation. This is a correctness/encapsulation fix, not a meaningful performance fix.

#### 🟡 LOW · Tight-loop busy-wait polling /dev/rfcomm device node with File.exists()

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/BluetoothSppP2PM.desktop.kt:114` · _Busy-wait / polling loop_
- **Why it's slow:** runRfcommListener polls File.exists() every 500 ms for up to 5 minutes without an isActive check in the while condition. However, delay(500) is itself a Kotlin coroutine cancellation point and will throw CancellationException on cancellation, so cancellation IS cooperative — the coroutine will exit within 500 ms of cancellation. The real concern is only that the missing isActive means the loop condition itself won't short-circuit; the 500 ms syscall cadence is genuinely low cost.
- **Fix:** Add isActive to the while condition as defensive hygiene: `while (isActive && System.currentTimeMillis() < deadline)`. The busy-wait concern is otherwise overstated because delay() already provides cooperative cancellation.

#### 🟡 LOW · stopDiscoveryAndAdvertising copies rfcommBound to a List before iterating — unnecessary allocation

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/BluetoothSppP2PM.desktop.kt:131` · _Allocations / GC churn_
- **Why it's slow:** Line 131 calls rfcommBound.toList().forEach { releaseRfcomm(it) } before clearing the set. The copy avoids a ConcurrentModificationException, but releaseRfcomm does not modify rfcommBound (it calls rfcomm release externally), so the copy is unnecessary. rfcommBound is a plain mutableSetOf() with no concurrent access at shutdown.
- **Fix:** Iterate directly: `rfcommBound.forEach { releaseRfcomm(it) }` then call clear(), since releaseRfcomm does not modify rfcommBound and there is no concurrent access during shutdown.

#### 🟡 LOW · publishPeers() allocates a new List<P2pPeer> on every call — called per scan line with no dedup guard

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/BluetoothSppP2PM.desktop.kt:160` · _Allocations / GC churn_
- **Why it's slow:** publishPeers() is called after every matching output line from bluetoothctl (line 153). Unlike the WiFiDirectP2PM Linux polling path, there is no `if (addr in foundDevices) continue` guard before inserting into foundDevices and calling publishPeers(). If bluetoothctl repeatedly emits the same device, a new ArrayList and P2pPeer objects are allocated each time. In practice bluetoothctl outputs each NEW device once, so duplicate emission is unlikely but not impossible.
- **Fix:** Add a guard before the map insert and publishPeers() call: `if (addr in foundDevices) continue` (matching the pattern in WiFiDirectP2PM line 115). This skips the allocation when the peer was already known.

#### 🟡 LOW · Hard-coded 500 ms settle delay after rfcomm bind — blocks IO coroutine without cancellation check

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/BluetoothSppP2PM.desktop.kt:204` · _Busy-wait / polling loop_
- **Why it's slow:** After rfcomm bind succeeds the code sleeps 500 ms unconditionally before checking if the device node exists (line 204). This is a single one-shot delay on a cold connect path. The claim that it cannot be cancelled is wrong — delay() is a coroutine cancellation point and will throw CancellationException. The 500 ms wasted time when the node appears sooner is a real but minor UX annoyance, not a performance problem.
- **Fix:** Replace the fixed delay with a short poll loop (e.g. 50 ms intervals up to 500 ms) checking File.exists() so the common case exits earlier.

#### 🟡 LOW · rfcomm read-pump calls RandomAccessFile.read() which is blocking IO; per-read flush() adds overhead

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/BluetoothSppP2PM.desktop.kt:239` · _Blocking or heavy work on wrong dispatcher_
- **Why it's slow:** The read-pump (lines 237-243) correctly runs on PreferablyIO where blocking IO is expected. The actual issue is the unconditional `read.flush()` call after every partial raf.read() (line 242). This flushes Ktor's ByteChannel buffer on every partial read, preventing natural write coalescing and adding round-trip overhead for callers consuming the channel. raf.read() returns partial reads on every syscall; flushing after each one maximizes the number of downstream read-side wakeups.
- **Fix:** Remove the per-read flush() call and let the Ktor ByteChannel buffer internally. Only flush after writing a logical boundary (e.g. after sending a complete frame), or rely on the consumer's read calls to pull data naturally.

#### 🟡 LOW · serviceResolved and serviceRemoved rebuild the full availablePeers list on every mDNS event

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/LanMdnsP2PM.desktop.kt:128` · _Allocations / GC churn_
- **Why it's slow:** Both serviceResolved (line 133) and serviceRemoved (line 123) unconditionally call foundPeers.values.map { P2pPeer(...) } and assign to availablePeers.value. These events are jmdns-driven and infrequent (one per discovered peer), so this allocates O(n) objects per event but the total number of events is bounded by network topology. This runs on the jmdns thread, compounding the concern in finding 10.
- **Fix:** The allocation is unavoidable on add/remove since the peer set genuinely changes. The main improvement is to move these callbacks off the jmdns thread (see finding 10). The allocation itself is low-priority given the event frequency.

#### 🟡 LOW · pickLocalIPv4 calls NetworkInterface.getNetworkInterfaces() on every connection attempt — duplicated in two files

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/LanMdnsP2PM.desktop.kt:173` · _Resource leaks / repeated expensive lookups_
- **Why it's slow:** pickLocalIPv4() in LanMdnsP2PM (line 173) and LanHostP2PM (line 94) are verbatim copies of identical logic iterating all network interfaces. Both are called only at session start (once per startDiscoveryAndAdvertising or establishTcpServer call), making this a cold-path concern. The interface iteration cost is real on machines with many virtual adapters, but caching with a TTL would only help if the function were called frequently. The code duplication is the primary issue.
- **Fix:** Extract to a shared utility function in a common utilities file to eliminate duplication. Caching with a 30-second TTL is a nice-to-have but not urgent given the cold-path invocation pattern.

#### 🟡 LOW · LanP2PM: fixed 2-second delay between TCP retry attempts — no exponential backoff

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/LanP2PM.desktop.kt:91` · _Busy-wait / tight polling loops_
- **Why it's slow:** tcpConnectWithRetry (line 81-93) uses a fixed 2-second delay across all 5 retries regardless of the failure reason. This is a cold-path UX concern: worst case is 10 seconds of idle delay before giving up. The missing isActive check in the repeat loop is minor since delay(2.seconds) is a cooperative cancellation point.
- **Fix:** Use exponential backoff starting at 500 ms and capping at ~4 seconds: `delay((500L * (1 shl attempt)).coerceAtMost(4000L).milliseconds)`. Add `if (!isActive) return false` for defensive hygiene.

#### 🟡 LOW · waitForGroupOwnerAddress: NetworkInterface.getNetworkInterfaces() called in a polling loop without isActive guard

- **Where:** `shared/src/desktopMain/kotlin/jetzy/managers/WiFiDirectP2PM.desktop.kt:279` · _Busy-wait / polling loop_
- **Why it's slow:** waitForGroupOwnerAddress polls NetworkInterface.getNetworkInterfaces() every 500 ms (line 278-295) with no isActive check. delay(500) is a coroutine cancellation point so cancellation IS cooperative — the claim that it cannot be cancelled early is wrong. The interface scanning cost is real but bounded to a 10-second window. The nested loops over all interfaces and addresses do allocate Enumeration wrappers per call.
- **Fix:** Add `isActive` to the while condition as defensive hygiene. Consider using NetworkInterface.getByName() to scan only the expected p2p- interface by name rather than iterating all interfaces.

#### 🟡 LOW · 2-second fixed delay after Wi-Fi join used as synchronization instead of event-based settle

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/LanWifiP2PM.kt:101` · _Fixed delay used for synchronization_
- **Why it's slow:** After verifyJoinedExpectedSsid confirms SSID association, connectAttempt unconditionally sleeps 2 seconds at line 101 to let the network settle. The SSID verification already confirms radio association; DHCP assignment is the remaining uncertainty, and the existing tcpConnectWithTimeout (5 attempts, 2-second back-off) already handles the case where DHCP is not yet complete. The blanket sleep adds 2 seconds of fixed latency on every successful hotspot connection.
- **Fix:** Remove the delay(2.seconds). The TCP retry loop (5 attempts x 2s back-off = up to 8s tolerance) is sufficient to absorb DHCP settling time. If empirical testing shows some DHCP implementations need a floor, a 200-500ms sleep is a more targeted compromise.

#### 🟡 LOW · verifyJoinedExpectedSsid uses generateTimestampMillis() (NSDate allocation) in a polling loop

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/LanWifiP2PM.kt:184` · _Allocations / GC churn in hot path_
- **Why it's slow:** verifyJoinedExpectedSsid (lines 182–189) calls generateTimestampMillis() twice per 400ms iteration — once for the initial deadline and once in the while condition. Maximum 15 iterations per attempt (6000ms / 400ms), so at most 30 NSDate allocations per join attempt. This is a cold path: it runs only at Wi-Fi join time, not during data transfer. The allocation overhead is genuinely negligible at this rate.
- **Fix:** Replace with a simple counted loop using repeat((JOIN_VERIFY_TIMEOUT / JOIN_VERIFY_POLL_INTERVAL).toInt()) to avoid all timestamp calls. This is cleaner and eliminates the NSDate allocations, though the performance benefit is immeasurable in practice.

#### 🟡 LOW · verifyJoinedExpectedSsid uses generateTimestampMillis() for deadline tracking instead of coroutine timeout

- **Where:** `shared/src/iosMain/kotlin/jetzy/managers/LanWifiP2PM.kt:184` · _NSDate allocation in polling loop_
- **Why it's slow:** verifyJoinedExpectedSsid at line 182-189 calls generateTimestampMillis() twice per 400 ms loop iteration (once at entry to check the deadline, once each time through the while condition). On iOS each call allocates an NSDate object. Over a 6-second JOIN_VERIFY_TIMEOUT this is at most 30 NSDate allocations — a trivial heap cost. The loop is a cold-path connection setup sequence, not a hot transfer path. The semantic concern (using timestamps for a coroutine deadline when withTimeoutOrNull is available) is valid for code clarity and correctness but the performance impact is negligible.
- **Fix:** Replace with withTimeoutOrNull(JOIN_VERIFY_TIMEOUT) { while(true) { if(fetchCurrentSsid() == expectedSsid) return@withTimeoutOrNull true; delay(JOIN_VERIFY_POLL_INTERVAL) } } ?: false to eliminate the manual deadline tracking and the two NSDate allocations per tick.

### Transport · Negotiation — 5 (0H / 2M / 3L)

#### 🟠 MED · localCapabilitiesMask() recomputed on every handshake — includes uncached PackageManager.hasSystemFeature IPC call

- **Where:** `shared/src/commonMain/kotlin/jetzy/p2p/P2pTechnology.kt:63` · _Redundant recomputation / expensive system call_
- **Why it's slow:** localCapabilitiesMask() is a plain function with no caching. It is called three times per handshake (P2PManager lines 701, 711, and 727). Each call iterates all 9 transports via filter + fold. For WiFiAware.isLocallyCapable, it calls isWifiAwareSupported() which on Android (PlatformUtils.android.kt line 31-34) calls ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) — a synchronous Binder IPC into system_server. The mask is a pure function of device state that is immutable for the lifetime of the process.
- **Fix:** Convert localCapabilitiesMask to a top-level lazy val in the companion: `val localCapabilitiesMask: Long by lazy { allMethods.filter { it.isLocallyCapable(platform) }.fold(0L) { acc, t -> acc or (1L shl t.capabilityBit) } }`. On Android, cache isWifiAwareSupported() similarly so the Binder IPC fires at most once.

#### 🟠 MED · localCapabilitiesMask() recomputes by iterating allMethods every call — called at every handshake and in logPeerCapabilities

- **Where:** `shared/src/commonMain/kotlin/jetzy/p2p/P2pTechnology.kt:63` · _Redundant recomputation_
- **Why it's slow:** localCapabilitiesMask() filters allMethods (9 entries) with isWifiAwareSupported() checks and folds on every call. It is called at P2PManager lines 701, 711, and 727 per handshake. isWifiAwareSupported() re-reads OS version info (NSProcessInfo on iOS, PackageManager on Android) each time. The result is constant for the device's lifetime. CapabilityProfile.local() in TransportNegotiator.kt also calls it.
- **Fix:** Store the result as `val localCapabilitiesMask: Long by lazy { ... }` in the Registry companion object. isWifiAwareSupported() can similarly be cached at first access.

#### 🟡 LOW · HotspotLAN.isLocallyCapable creates a new anonymous Set<Platform> on every invocation

- **Where:** `shared/src/commonMain/kotlin/jetzy/p2p/P2pTechnology.kt:228` · _Allocation in repeated path_
- **Why it's slow:** HotspotLAN.isLocallyCapable at line 228 uses `currentPlatform in setOf(Platform.Android, Platform.IOS, Platform.PC)`, allocating a new Set<Platform> on every call. Note that this set intentionally includes Platform.PC, which differs from the `supportedPlatforms` field (which only has Android and IOS). The function is called once per transport during localCapabilitiesMask() computation.
- **Fix:** Extract the set to a private companion val: `private val capableSet = setOf(Platform.Android, Platform.IOS, Platform.PC)` and use `currentPlatform in capableSet`. Do not replace with `supportedPlatforms` as the sets differ (PC is capable but not in supportedPlatforms).

#### 🟡 LOW · TransportNegotiator.negotiate() builds a new sorted list on every call — not memoized

- **Where:** `shared/src/commonMain/kotlin/jetzy/p2p/TransportNegotiator.kt:53` · _Redundant recomputation / allocation in repeated path_
- **Why it's slow:** best() calls negotiate() which builds a full sorted List<TransportMatch> (up to 9 entries) and then discards all but the first element. The tiebreak constructs a String key via interpolation per call. This runs once per handshake on a 9-element list — cost is trivially small in absolute terms, but the best() implementation is structurally wasteful.
- **Fix:** Replace best()'s call to negotiate().firstOrNull() with a single maxByOrNull pass over the filtered/mapped sequence — avoids the sort allocation when only the maximum-quality element is wanted. This is a code quality fix more than a measurable performance gain given the 9-element bound.

#### 🟡 LOW · TransportNegotiator.negotiate() builds intermediate filtered lists and sorts on every call

- **Where:** `shared/src/commonMain/kotlin/jetzy/p2p/TransportNegotiator.kt:55` · _Algorithmic inefficiency_
- **Why it's slow:** negotiate() chains filter + mapNotNull + sortedByDescending over allMethods (9 entries), creating two intermediate lists on every call. Since allMethods is a fixed 9-element list and quality ordering is static, the sort is redundant on every call. getMethodById() at P2pTechnology.kt line 55 does a linear find over allMethods per lookup.
- **Fix:** Pre-sort allMethods by quality descending at initialization time (or in the lazy initializer) so the sortedByDescending step becomes a no-op. Replace getMethodById with a Map<String, P2pTechnology> built once. The absolute cost is negligible (9 elements), so this is purely a code-quality fix.

### ViewModel / state — 4 (0H / 3M / 1L)

#### 🟠 MED · Five independent snapshotFlows over the same SnapshotStateList — list filtered 5 times per mutation

- **Where:** `shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt:226` · _Algorithmic inefficiency / Flow misuse_
- **Why it's slow:** file2Send, folders2Send, photos2Send, videos2Send, and texts2Send each run their own snapshotFlow { filterIsInstance<T>() } over the same SnapshotStateList. Every mutation to elementsToSend triggers 5 separate snapshot reads, each scanning the full list with filterIsInstance. For N items and K types, each add/remove costs O(N*5) scans and produces 5 new List allocations.
- **Fix:** Use a single snapshotFlow that partitions elements by type once per mutation, then expose derived StateFlows via map(). This reduces list scans from 5 to 1 per mutation and collector wakeups from 5 to 1.

#### 🟠 MED · snapshotFlow { filterIsInstance<T>() } creates a new List allocation on every elementsToSend mutation

- **Where:** `shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt:284` · _Allocation / GC churn in hot path_
- **Why it's slow:** Each of the five `filterAsStateFlow` calls (lines 226-230) is backed by `snapshotFlow { filterIsInstance<T>() }`. Every mutation to `elementsToSend` re-runs all five lambdas, each allocating a new `ArrayList` and doing a full linear scan. As `elementsToSend` grows, this is O(5×n) allocations and scans per user action.
- **Fix:** Maintain separate typed `SnapshotStateList` instances as primary state and expose `elementsToSend` as a computed read-only view, or add `.distinctUntilChanged()` to suppress duplicate emissions when the filtered sublists haven't changed.

#### 🟠 MED · filterAsStateFlow uses SharingStarted.Lazily — cold restart on re-subscription drops cached value

- **Where:** `shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt:287` · _Coroutine/Flow misuse_
- **Why it's slow:** `SharingStarted.Lazily` at line 287 means the shared flow (and its cached `StateFlow` value) is stopped when subscriber count drops to zero. When a user navigates away from a subscreen and back, `collectAsState()` resubscribes, triggering a fresh cold `snapshotFlow { filterIsInstance<T>() }` scan of `elementsToSend`. This happens for each of the five typed flows. `SharingStarted.WhileSubscribed(5_000)` retains the last emitted value for 5 seconds, eliminating redundant rescans during navigation.
- **Fix:** Change `SharingStarted.Lazily` to `SharingStarted.WhileSubscribed(5_000)` in `filterAsStateFlow`.

#### 🟡 LOW · snacky() launches a new coroutine on every call without cancelling the previous one

- **Where:** `shared/src/commonMain/kotlin/jetzy/viewmodel/JetzyViewmodel.kt:293` · _Unbounded coroutine launches_
- **Why it's slow:** snacky() at line 291 launches a new coroutine into viewModelScope on every invocation without cancelling the prior one (when queue=false, it dismisses the active snackbar UI but does not cancel the suspended coroutine waiting in showSnackbar). Under repeated rapid errors (stall events, retries), viewModelScope accumulates dangling coroutines each awaiting showSnackbar. SnackbarHostState serializes calls so they drain eventually, but the coroutine objects pile up until each completes. In typical usage (a handful of errors per session) this is negligible.
- **Fix:** Maintain a private snackbarJob: Job? field; on each snacky() call, cancel it before launching a replacement when queue=false. This ensures at most one pending coroutine.

### UI · Transfer screen — 27 (4H / 17M / 6L)

#### 🔴 HIGH · All state collected at the top level of TransferScreenUI — any state change recomposes the entire screen

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:93` · _Compose recomposition problems — state read scope too high_
- **Why it's slow:** manifest, fileEntries, progress, speed, remote, transferComplete, saveComplete, and canResume are all collected at the top of TransferScreenUI (lines 93-101). Because all of these are read inside the single giant item {} block (lines 131-197), any state change — including per-chunk changes to fileEntries and transferProgress — recomposes the entire item block, including PeerAvatar (which has its own infinite animations), PacketAnimation, ProgressSection, and all N file rows. The outer TransferScreenUI function itself does not recompose (only the item {} lambda does), but the item block is effectively one large recompose scope.
- **Fix:** Push state reads down to the composables that need them. ProgressSection can collect progress and speed internally. The file-list Column (or ideally a LazyColumn) can receive fileEntries. PeerAvatar, ConnectingState, and the button block can be isolated into separate item {} entries in the LazyColumn so they do not recompose on chunk events. Use derivedStateOf for completedFiles and other derived values.

#### 🔴 HIGH · fileEntries list emitted every chunk causes full Column recomposition for all FileRow/TextRow composables

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:188` · _Compose recomposition problems_
- **Why it's slow:** The file list at line 184 is a plain Column using forEachIndexed, not a LazyColumn with keyed items(). Because fileEntries is a new List object on every updateAt() call, Compose sees a new reference and cannot skip any child composable. Every FileRow and TextRow recomposes on every 512 KB chunk. The outer item {} block (line 131) contains PeerAvatar, PacketAnimation, ProgressSection, and the whole file list — all in one recomposition scope — so none of it can be skipped independently.
- **Fix:** Move the file list into the outer LazyColumn using items(fileEntries, key = { it.name }) { entry -> ... } so Compose can individually skip unchanged rows. Push state reads (progress, speed, fileEntries) down into smaller composables so the peer row and button sections do not recompose on each chunk.

#### 🔴 HIGH · New List<State<Float>> allocated every recomposition in PacketAnimation

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:401` · _Compose recomposition — allocating list/states in composition_
- **Why it's slow:** `listOf(0, 400, 800).map { delay -> infiniteTransition.animateFloat(...) }` is called directly in composition at line 401 without `remember`. Every recomposition creates a new `List<State<Float>>` and calls `animateFloat` three times on the `InfiniteTransition`. In Compose, `InfiniteTransition.animateFloat` is itself a composable call that registers a new animation subscriber each time; without `remember` these subscribers accumulate/churn on every recomposition triggered by the parent transfer screen state changes.
- **Fix:** Wrap in `remember(infiniteTransition)`: `val offsets = remember(infiniteTransition) { ... }` is not correct here because `animateFloat` is a `@Composable` function and must be called in composition. The correct fix is to call each `animateFloat` individually and assign to named `val`s with no surrounding list construction: `val o1 by infiniteTransition.animateFloat(...)`, etc., which Compose can properly remember across recompositions since each is its own composable invocation at a stable call-site.

#### 🔴 HIGH · Path object allocated inside drawBehind lambda on every animation frame in PacketAnimation

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:422` · _Allocations / GC churn in hot path_
- **Why it's slow:** val pathDash = Path().apply { moveTo(...); cubicTo(...) } at line 422 is inside the drawBehind lambda of PacketAnimation. The drawBehind lambda runs on every animation frame (the infiniteRepeatable drives continuous animation). Path wraps a native graphics path object and allocating it per-frame causes sustained GC pressure for the lifetime of the PacketAnimation composable, which is the entire transfer duration. The Bézier control points are computed from size.width/height which are also stable once the composable is laid out.
- **Fix:** Hoist the Path into remember { Path() } in the PacketAnimation composable body. In the drawBehind lambda call pathDash.reset() then re-issue moveTo/cubicTo. Pre-compute the Bézier control point Offsets using onSizeChanged or a SizeModifier so they are not recomputed in the draw lambda either.

#### 🟠 MED · sizeLabel, typeLabel, displayName, isText, progress recomputed as get() on every access in hot composition path

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferManifest.kt:53` · _Algorithmic inefficiency — redundant recomputation_
- **Why it's slow:** In FileTransferEntry, sizeLabel (line 53), typeLabel (line 62), displayName (line 57), isText (line 55), and progress (line 51) are all computed-get() properties. sizeLabel calls toHumanSize() (round + division + string template) on every read. typeLabel does multiple startsWith()/contains() checks plus substringAfterLast + uppercase + take(3) on every read. These are read inside composition on every recomposition (per chunk). For an active file, progress is also read per chunk inside animateFloatAsState(targetValue = entry.progress...). Converting sizeLabel and typeLabel to regular vals (not get()) is safe since sizeBytes and mimeType are immutable after construction — only bytesTransferred, status, and textContent change via copy().
- **Fix:** Convert sizeLabel, typeLabel, displayName, and isText to regular vals (remove get()). They are computed once at construction and cached for free since FileTransferEntry is a data class that uses copy() to update. For progress, leave it as a get() since bytesTransferred changes on every update and the fraction must reflect the current value.

#### 🟠 MED · sizeLabel and typeLabel allocate a new String on every Compose recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferManifest.kt:53` · _Allocation / GC_
- **Why it's slow:** FileTransferEntry.sizeLabel (line 53) and typeLabel (line 62) are `get()` computed properties that allocate a new String on every access. Because FileTransferEntry lives in a StateFlow<List<...>>, any status update to any file (which happens every 512 KB chunk) triggers a new list emission, causing Compose to recompose every visible row and re-evaluate these properties for all entries. sizeBytes, mimeType, and entryType are immutable after construction, so these strings are recomputed to identical values on each recomposition.
- **Fix:** Convert sizeLabel and typeLabel to regular `val` properties initialized at construction time since they depend only on immutable fields. Note: `progress` at line 51 depends on the mutable `bytesTransferred` field and must remain a `get()` — or better, be wrapped in `derivedStateOf` at the Compose call site.

#### 🟠 MED · fileEntries.count{} linear scan on every Compose recomposition triggered by StateFlow updates

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:166` · _Compose recomposition problems_
- **Why it's slow:** Line 166 of TransferScreenUI.kt computes completedFiles = fileEntries.count { it.status == FileTransferStatus.Done } inside the composition. fileEntries is collected via collectAsState() at line 94, so every StateFlow emission (which happens per chunk per finding #3) triggers a full recomposition of this item block, re-running the O(N) count. For a 1000-file transfer processing hundreds of chunks per second this count runs thousands of times per second.
- **Fix:** Expose a dedicated completedFileCount: StateFlow<Int> from P2PManager, incremented only when a file transitions to Done. This makes the Compose side an O(1) read. Alternatively, wrap in derivedStateOf { fileEntries.count { it.status == FileTransferStatus.Done } } so Compose memoizes the result and only recomputes when the fileEntries list reference changes.

#### 🟠 MED · fileEntries.count { } O(n) scan on every recomposition of TransferScreenUI during active transfer

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:166` · _Algorithmic inefficiency in recomposition path_
- **Why it's slow:** At line 166: `val completedFiles = fileEntries.count { it.status == FileTransferStatus.Done }` is computed inline inside an `item { }` block that recomposes every time fileEntries changes. fileEntries changes every 512 KB chunk during transfer (via updateAt at P2PManager lines 422 and 617). For N files this is an O(N) scan per chunk.
- **Fix:** Wrap in `val completedFiles = remember(fileEntries) { fileEntries.count { it.status == FileTransferStatus.Done } }` to memoize per-snapshot, or maintain a separate completedFilesCount StateFlow incremented only when a file transitions to Done.

#### 🟠 MED · completedFiles count recomputed on every recomposition via linear scan of fileEntries

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:166` · _Algorithmic inefficiency / Compose recomposition_
- **Why it's slow:** val completedFiles = fileEntries.count { it.status == FileTransferStatus.Done } at line 166 is an unguarded O(n) scan in composition. It runs every time the item {} block recomposes, which is every chunk. However, the scan itself is very cheap (single-pass, no allocation beyond the lambda) and N (number of files) is typically small (<100). The bigger issue is structural: this value could be maintained as a counter in the manager.
- **Fix:** Wrap in remember(fileEntries) { fileEntries.count { it.status == FileTransferStatus.Done } } to avoid running it when unrelated state triggers recomposition. For a clean architecture fix, expose a completedCount: StateFlow<Int> from the manager that is incremented atomically when each file finishes.

#### 🟠 MED · toHumanSize() called on every recomposition, allocating new Strings at ~60 fps

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:172` · _Allocations / GC churn in hot path_
- **Why it's slow:** speed.toHumanSize() + "/s" at line 172 is called bare in the item {} composition scope, allocating a new String on every chunk-triggered recomposition. entry.bytesTransferred.toHumanSize() at line 683 is inside FileRow but only executes when entry.status == FileTransferStatus.Active — meaning at most one file at a time emits it. The cost is bounded (one active file at a time for the line-683 case) but line 172 fires on every chunk regardless. The description's claim of '~60 fps' is wrong: updates are chunk-rate (buffer reads), not frame-rate — speed only updates every 1 second per the 1-second window, but toHumanSize at line 172 depends on `speed` which changes every second, not every chunk. However, `fileEntries` changes every chunk and triggers the whole item {} recomposition, so line 172 still runs every chunk.
- **Fix:** Wrap line 172 in remember(speed) { if (speed > 0) speed.toHumanSize() + "/s" else "—" }. For line 683, because the Active branch is only reached for one file at a time and only during transfer, wrap in remember(entry.bytesTransferred) { entry.bytesTransferred.toHumanSize() }.

#### 🟠 MED · speed.toHumanSize() + "/s" string concatenation in composition on every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:172` · _Compose recomposition — allocating objects in composition_
- **Why it's slow:** `if (speed > 0) speed.toHumanSize() + "/s" else "—"` is evaluated at line 172 as an inline parameter expression on every recomposition. `speed` is a hot StateFlow updated per-chunk; on each update this allocates the result of `toHumanSize()` plus string concatenation. This is in the same `item {}` block as `remainingLabel` and has the same trigger rate.
- **Fix:** Replace with `val speedLabel = remember(speed) { if (speed > 0) speed.toHumanSize() + "/s" else "—" }` and pass `speedLabel` to `ProgressSection`.

#### 🟠 MED · remainingLabel() called bare in composition, allocating a new String every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:173` · _Allocations / GC churn in hot path_
- **Why it's slow:** remainingLabel(...) at line 173 is called directly in the item {} composition scope without a remember guard. The function performs division, comparison, and string interpolation on every call. The item {} block recomposes on every chunk (due to fileEntries changing), so this string is reallocated per chunk — not because progress/speed changed, but because the whole scope recomposes. The actual inputs (progress, speed, manifest?.totalBytes) may be stable between many chunks.
- **Fix:** val remainingStr = remember(progress, speed, manifest?.totalBytes) { remainingLabel(manifest?.totalBytes ?: 0L, progress, speed) }. Since speed only changes every 1 second, this caches aggressively.

#### 🟠 MED · remainingLabel string built in composition on every progress/speed update

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:173` · _Compose recomposition — heavy/allocating work inside composition_
- **Why it's slow:** `remainingLabel(totalBytes, progressFrac, speed)` is called directly as a parameter expression at lines 173–177. During active transfer, `progress` and `speed` are hot StateFlow values updated per-chunk, causing this pure function (which does arithmetic and string construction) to run on every recomposition. The function is a plain `private fun` (not `@Composable`), so `remember` is the correct guard.
- **Fix:** Replace with `val remainingLabelStr = remember(progress, speed, manifest?.totalBytes) { remainingLabel(manifest?.totalBytes ?: 0L, progress, speed) }` and pass `remainingLabelStr` to `ProgressSection`.

#### 🟠 MED · fileEntries rendered with forEachIndexed Column instead of LazyColumn items, losing virtualization and causing full-list recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:184` · _Compose recomposition — state read too high forcing wide recomposition / missing keys in Lazy_
- **Why it's slow:** All file-entry rows are rendered inside a `Column` via `forEachIndexed` within a single `LazyColumn item {}` (lines 184–195). Any change to `fileEntries` causes the entire `Column` to recompose, recomposing every `FileRow`/`TextRow` child. However, virtualization loss only matters at scale; most transfers involve a bounded small number of files. The full-list recomposition issue is real.
- **Fix:** Extract the file list into top-level `items(fileEntries, key = { it.name })` calls within the existing `LazyColumn`, removing the wrapping `item { Column { forEachIndexed } }`. This limits recomposition to the single changed row and enables off-screen virtualization.

#### 🟠 MED · forEachIndexed over fileEntries in a Column inside LazyColumn item — no keyed diffing for list updates

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:188` · _Compose recomposition problems_
- **Why it's slow:** Lines 184-195 render the file list using a plain Column with fileEntries.forEachIndexed inside a single LazyColumn item block. Every StateFlow emission (per chunk) recomposes the entire item block and re-creates all N FileRow/TextRow inline composables from scratch without Compose being able to diff or reuse any nodes. The entire Column is one opaque item to the LazyColumn so Compose cannot virtualize or key individual rows. For large N this inflates the recomposition cost proportionally.
- **Fix:** Replace the Column + forEachIndexed with items(fileEntries, key = { it.name }) at the LazyColumn level (currently this is nested inside an item {} block which prevents it). Restructure the LazyColumn so the file rows are top-level items with keys, allowing Compose to reuse and diff row composables individually. This is the standard Compose pattern for updating list UI.

#### 🟠 MED · fileEntries.forEachIndexed inside a LazyColumn single item {} defeats list virtualization

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:188` · _Compose recomposition / layout inefficiency_
- **Why it's slow:** At lines 184-195, fileEntries.forEachIndexed { i, entry -> FileRow(...) } is placed inside a Column inside a single LazyColumn item { } block. All N file rows are composed simultaneously — Compose cannot virtualize them. Every chunk-driven fileEntries update causes all N rows to recompose. For large batch transfers this compounds the fileEntries.count scan (finding 15) and the updateAt copy (finding 6) into full-screen recomposition pressure.
- **Fix:** Replace the inner Column+forEachIndexed with `items(fileEntries, key = { it.name }) { entry -> if (entry.isText) TextRow(entry) else FileRow(entry) }` directly inside the LazyColumn, enabling true virtualization and stable-key identity tracking across updates.

#### 🟠 MED · isSaving collectAsState called conditionally inside a conditional branch

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:208` · _Compose recomposition problems — unstable state subscription_
- **Why it's slow:** val isSaving by manager.isSaving.collectAsState() at line 208 is inside an if block (line 205). This violates Compose's rule that composable functions (including remember-backed helpers like collectAsState) must not be called conditionally. When the condition changes from false to true, a new StateFlow subscription is created on that recomposition frame, causing an immediate extra recomposition (initial value then real value). When the condition becomes false, the subscription is abandoned. Beyond correctness, isSaving is a StateFlow<Boolean> with a cheap subscription and is better hoisted unconditionally.
- **Fix:** Hoist val isSaving by manager.isSaving.collectAsState() to the top of TransferScreenUI alongside the other state declarations (lines 93-101). The isSaving=false state is cheap and reading it unconditionally has no meaningful cost.

#### 🟠 MED · buildString with totalBytes.toHumanSize() called on every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:475` · _Allocations / GC churn in hot path_
- **Why it's slow:** The buildString { append("$completedCount of $totalCount files"); if (totalBytes != null) append("  ·  ${totalBytes.toHumanSize()} total") } at line 475 is inside ProgressSection's composition scope. ProgressSection receives completedCount and totalBytes as plain parameters and is recomposed whenever its parent (the item {} block) recomposes — every chunk. totalBytes never changes after the manifest arrives; the toHumanSize() call for it is always redundant after the first. The StringBuilder allocation is real but trivial per call; the real issue is that the whole string recomputes when only completedCount changed.
- **Fix:** Cache with remember(completedCount, totalCount, totalBytes) { buildString { ... } }. This is particularly effective for totalBytes.toHumanSize() which never changes.

#### 🟠 MED · Color.copy(alpha = dotAlpha) allocates a new Color object on every animation frame in SpeedBadge

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:543` · _Allocations / GC churn in hot path_
- **Why it's slow:** colorScheme.tertiary.copy(alpha = dotAlpha) at line 543 is inside a Modifier.background() call directly in composition. dotAlpha is driven by infiniteRepeatable which changes every frame (~60 fps). Each frame triggers recomposition of SpeedBadge (since dotAlpha is read in composition via `by`), and Color.copy() allocates a new Color object. On Compose/JVM, Color is a value class backed by a ULong so boxing only occurs when passed as an Any — but Modifier.background() takes a Color parameter, which on some backends may box it. The more certain cost is the per-frame recomposition of SpeedBadge itself.
- **Fix:** Replace .background(colorScheme.tertiary.copy(alpha = dotAlpha)) with .graphicsLayer { alpha = dotAlpha } .background(colorScheme.tertiary). The graphicsLayer alpha change is applied at the rendering layer and does not trigger recomposition of SpeedBadge, eliminating per-frame recomposition entirely. For the PacketAnimation drawBehind case (line 436), Color.copy inside a draw lambda is not a recomposition trigger and is less concerning.

#### 🟠 MED · produceState with coroutine delay used for entry-fade animation in every FileRow and TextRow

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:561` · _Coroutine / resource overhead per row_
- **Why it's slow:** Both TextRow (line 561) and FileRow (line 640) use produceState to launch a coroutine per row that delays then flips a Float. With N rows, N coroutines are allocated. Since the file list is inside a plain Column (not LazyColumn), all rows are composed at once and all N coroutines are live simultaneously. The coroutines finish after animDelay + ~0ms (they just set a value), so they are short-lived. However, because the enclosing item {} block recomposes per chunk, and produceState is keyed on entry.name and animDelay, the coroutine is only re-launched when those keys change — not on every chunk. So the per-chunk recomposition does not spawn new coroutines. The N-coroutines-at-a-time cost is one-time at initial composition.
- **Fix:** Replace with animateFloatAsState(targetValue = 1f, animationSpec = tween(300, delayMillis = animDelay)) which uses Compose's shared animation infrastructure rather than per-row coroutines. This is cleaner and eliminates the N heap-allocated coroutine contexts, though the practical cost difference is small since the coroutines complete quickly.

#### 🟠 MED · typeLabel.uppercase() allocates a new String on every FileTypeIcon recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:719` · _Allocations / GC churn in hot path_
- **Why it's slow:** FileTypeIcon at line 719 calls typeLabel.uppercase() twice per recomposition — once for the when-dispatch and once for the Text at line 736. FileTypeIcon is called once per row, and all rows recompose per chunk. For file rows, entry.typeLabel is already computed by the get() property on FileTransferEntry which itself returns uppercase strings for the common cases ("VID", "IMG", "AUD", "PDF", "ZIP", "DOC", "TXT", "BIN"). For the TextRow, typeLabel is hardcoded as the literal "TXT". So uppercase() on already-uppercase strings still creates a new String object but is essentially a no-op transformation. For the edge-case extension branch (e.g., "mp4"), typeLabel from FileTransferEntry already uppercases and takes 3 chars. The double uppercase() call is at minimum redundant.
- **Fix:** Since FileTransferEntry.typeLabel already returns uppercase strings, the typeLabel.uppercase() calls in FileTypeIcon are always redundant. Drop both uppercase() calls in FileTypeIcon, or cache val upper = remember(typeLabel) { typeLabel.uppercase() }. The structural fix is to make FileTypeIcon accept an already-uppercased string by contract.

#### 🟡 LOW · hasFiles computed via linear scan (any { }) on every access of TransferManifest

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferManifest.kt:18` · _Algorithmic inefficiency — redundant recomputation_
- **Why it's slow:** hasFiles and hasTexts in TransferManifest (lines 18-19) are get() properties calling entries.any { ... }. TransferManifest is immutable after the handshake and is read in the item {} composition scope (line 203: manifest?.hasFiles). The scan is O(n) over the entries list. However, TransferManifest is not updated during transfer (only fileEntries updates), so this scan runs only when the item {} block recomposes — which is every chunk. The list is typically small and any {} short-circuits, making the actual cost trivial in practice.
- **Fix:** Remove get() to make hasFiles and hasTexts regular vals computed once at TransferManifest construction time. This is the correct design since entries is immutable.

#### 🟡 LOW · Dynamic string interpolation in rememberInfiniteTransition label in PeerAvatar

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:353` · _Compose recomposition — allocating objects in composition_
- **Why it's slow:** `rememberInfiniteTransition(label = "float_$name")` at line 353 and `animateFloat(label = "float_y_$name")` at line 362 allocate their label strings on every recomposition of `PeerAvatar`. `rememberInfiniteTransition` itself is remembered (stable), but the `label` argument string is allocated fresh each pass. The `label` parameter is debug-only tooling metadata.
- **Fix:** Compute label strings once: `val transLabel = remember(name) { "float_$name" }` and `val animLabel = remember(name) { "float_y_$name" }`, then pass them as parameters. Since `name` is stable for the transfer duration, these compute once.

#### 🟡 LOW · Size, Offset, and CornerRadius value-class instances allocated per packet-dot per frame

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:437` · _Allocations / GC churn in hot path_
- **Why it's slow:** Inside the drawBehind lambda at lines 437-439, dp.toPx() is called multiple times per dot per frame. However, Offset, Size, and CornerRadius in Compose are inline/value classes on most platforms — they are backed by primitive longs and do not heap-allocate in optimized builds on JVM/Android. The dp.toPx() calls involve a multiplication by density which is real work but not an allocation. The severity is lower than claimed because the boxing concern mostly does not apply to value classes on JVM.
- **Fix:** Pre-compute the constant pixel values (dotHalfPx = 4.dp.toPx(), dotSizePx = 8.dp.toPx(), dotRadiusPx = 2.dp.toPx()) in the composable body using remember and the LocalDensity, so toPx() is not called in the draw lambda. This eliminates the repeated density multiplication without worrying about boxing.

#### 🟡 LOW · animateFloatAsState label uses string interpolation with entry.name in FileRow

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:651` · _Allocations / GC churn in hot path_
- **Why it's slow:** label = "file_progress_${entry.name}" at line 651 creates a String by interpolation in FileRow composition. Because the entire item {} block recomposes per chunk (due to fileEntries changing), FileRow recomposes per chunk, meaning this interpolation runs N times per chunk (once per file row). The allocation cost of N short string concatenations is real but minor compared to the structural issues. The label is debug-only.
- **Fix:** Use remember(entry.name) { "file_progress_${entry.name}" } or simply a fixed label like "file_progress". The real fix is to stop recomposing all FileRows per chunk by using keyed lazy items.

#### 🟡 LOW · String concatenation for animateFloatAsState label built in composition inside FileRow

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:652` · _Compose recomposition — allocating objects in composition_
- **Why it's slow:** `label = "file_progress_${entry.name}"` performs string interpolation on every recomposition of `FileRow` at line 651. The `label` parameter on `animateFloatAsState` is used only in tooling/debugging and does not affect animation behavior or identity. The allocation cost is a single short string per recomposition per file row.
- **Fix:** Extract to `val label = remember(entry.name) { "file_progress_${entry.name}" }`. Given the debug-only purpose of this label, the actual performance impact is negligible; the fix is pure hygiene.

#### 🟡 LOW · typeLabel.uppercase() and Pair allocation in FileTypeIcon without remember

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/transfer/TransferScreenUI.kt:719` · _Compose recomposition — allocating objects in composition_
- **Why it's slow:** `typeLabel.uppercase()` and destructuring of the `when` result into `(bg, fg)` happen at lines 719–727 on every recomposition of `FileTypeIcon`. `typeLabel` is stable for any given file entry (it is derived from the file extension, which never changes during transfer). The `when` result is two `Color` references from `colorScheme` (value types, not heap-allocated objects in the Compose Color inline class representation on most targets), and the Pair returned by `to` is a real allocation.
- **Fix:** Wrap in `remember(typeLabel)`: `val (bg, fg) = remember(typeLabel) { when (typeLabel.uppercase()) { ... } }`. Since `typeLabel` never changes for a given file, this computes once per composable instance.

### UI · Discovery / QR — 36 (8H / 16M / 12L)

#### 🔴 HIGH · RadarView: static Color.copy() allocations on every drawBehind frame

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:334` · _Allocation in per-frame draw path_
- **Why it's slow:** Inside the drawBehind lambda of the outer RadarView Box (lines 328-372), the ring-drawing loop calls `colorScheme.primary.copy(alpha = .15f)` once per ring iteration (3 times), plus `colorScheme.primary.copy(alpha = .2f)` for the sweep arc, `.copy(alpha = .5f)` for the line, and `.copy(alpha = centerPulse)` for the center dot — 6 Color allocations per frame minimum. This lambda executes at ~60 fps while the discovery screen is visible because sweepAngle and centerPulse animate continuously.
- **Fix:** Pre-compute the fixed-alpha colors outside the drawBehind lambda using `remember(colorScheme.primary)`. Only the centerPulse-driven color needs updating per frame and can be computed cheaply from a cached base color. The three ring colors are identical (all .copy(alpha = .15f)) and should be a single remembered val.

#### 🔴 HIGH · Per-peer rememberInfiniteTransition inside forEachIndexed — N ripple animations re-subscribed on any peer list change

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:382` · _Compose recomposition / resource leak_
- **Why it's slow:** Inside RadarView's peers.forEachIndexed loop at line 377, each iteration calls rememberInfiniteTransition(label = "ripple_$index") and starts two animateFloat transitions. Because these are keyed by index (not peer identity), any change to the peers list causes all N InfiniteTransitions to be re-created and their animations to restart. A peer inserted at position 0 shifts all subsequent index-keyed states, mismatching animations to peers.
- **Fix:** Wrap each peer's subtree in `key(peer.id) { ... }` so Compose retains animation state per stable peer identity across list mutations. Alternatively, extract the peer dot into a separate @Composable fun PeerDot so Compose independently tracks it.

#### 🔴 HIGH · rememberInfiniteTransition created inside forEachIndexed loop — new animation per recomposition per peer

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:382` · _Compose recomposition / animation overhead_
- **Why it's slow:** Inside RadarView (lines 377-431), for each peer in the list, rememberInfiniteTransition and two animateFloat calls are created inside peers.forEachIndexed. forEachIndexed is not a @Composable loop with stable positional keys — Compose assigns remembered state by call-site slot. When peers.size changes (peer added or removed), slot indices shift and the runtime discards and recreates animation state for every peer below the change point. With n peers this means up to 2n InfiniteTransition and 4n AnimationState objects reconstructed on every list-size change.
- **Fix:** Extract each peer's dot into a separate @Composable fun PeerDot(peer, angle, radius, colors) so each call-site has a stable slot for remember. The peer list in RadarView should iterate with keys matching peer.id — use items(peers, key = { it.id }) if migrated to LazyColumn, or a manual key { } block.

#### 🔴 HIGH · Per-peer full-size Box with drawBehind for ripple animation causes N overdraw passes per frame

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:405` · _Compose recomposition — animation that recomposes instead of using the graphics layer_
- **Why it's slow:** Each discovered peer (lines 405–430) creates a `Box(Modifier.fillMaxSize().drawBehind { ... })`. With N peers this means N full-size canvas passes per animation frame, and the entire radar area is registered N times for hit-testing (each `Box` covers `fillMaxSize`). At 60fps with even 2–3 peers this is a meaningful rendering overhead.
- **Fix:** Consolidate all peer dots and ripples into a single `drawBehind` on the parent `Box` (lines 376–432), passing ripple state in via `SnapshotStateList` or captured per-peer vals. Alternatively, use `Modifier.graphicsLayer` at the computed position (not `fillMaxSize`) per peer so only the affected layer is invalidated.

#### 🔴 HIGH · Redundant O(n) linear scan inside drawBehind per peer per frame: peers.firstOrNull { it.id == peer.id }

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:424` · _Algorithmic inefficiency in per-frame draw path_
- **Why it's slow:** At line 424 inside a drawBehind lambda (per-frame): `if (peer == peers.firstOrNull { it.id == peer.id })` scans the entire peers list to find a peer matching peer.id, then compares it to the closure-captured peer. For a sane peer list with unique IDs, this condition is always true — the peer captured in the closure is always the first (and only) element with its own ID. This O(n) scan runs every animation frame for every displayed peer.
- **Fix:** Remove the condition entirely and always use colors.accent. If the intent was to highlight a selected peer, accept `isSelected: Boolean` as a parameter and compare directly without scanning the list.

#### 🔴 HIGH · O(n) linear scan inside drawBehind — firstOrNull called per frame per peer, condition always true

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:424` · _Algorithmic inefficiency in hot draw path_
- **Why it's slow:** At line 424, inside the drawBehind lambda that runs every animation frame, the expression `peers.firstOrNull { it.id == peer.id }` performs an O(n) scan of the peers list for each peer. Because peer itself comes from peers.forEachIndexed, its id always matches — the scan always succeeds and returns the same peer, so the condition is always true. The else branch (colors.accent.copy(.7f)) is dead code. For n peers this is O(n^2) wasted work per frame on the main thread.
- **Fix:** Remove the lookup entirely and use colors.accent unconditionally. If future intent is to highlight the selected peer, pass selectedPeer into the lambda via closure and compare peer.id == selectedPeer?.id directly without searching the list.

#### 🔴 HIGH · O(n) peers.firstOrNull scan per peer per animation frame inside drawBehind in RadarView

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:424` · _Compose recomposition — heavy/allocating work inside composition / algorithmic inefficiency_
- **Why it's slow:** Inside a `drawBehind` lambda that executes at 60fps (driven by `rippleScale`/`rippleAlpha` animations), line 424 calls `peers.firstOrNull { it.id == peer.id }` for each peer dot. Because `peer` is guaranteed to be an element of `peers`, the condition `peer == peers.firstOrNull { it.id == peer.id }` is always true only if `peer.id` uniquely identifies the peer AND `peer` is reference-equal to the found element. If `peers` is a new list on each state update (as is typical for StateFlow snapshots), the reference equality `peer == ...` may always be false, or always true depending on data class semantics. Regardless, this is an O(n) scan per peer per frame — with N peers that is O(n²) scans per frame — and the branch outcome is constant for any given peer during a frame.
- **Fix:** Remove the conditional entirely and use `colors.accent` directly. If the intent was to distinguish the first-discovered peer visually, pre-compute `val firstPeerId = remember(peers) { peers.firstOrNull()?.id }` outside `drawBehind` and use `if (peer.id == firstPeerId) colors.accent else colors.accent.copy(.7f)` — an O(1) comparison.

#### 🔴 HIGH · QR metadata callback delivered on main queue — AVCaptureSession teardown and client init on main thread

- **Where:** `shared/src/iosMain/kotlin/jetzy/uiviewcontroller/QRScannerController.kt:55` · _Blocking / heavy work on main thread_
- **Why it's slow:** At line 55, `qrMetadataOutput!!.setMetadataObjectsDelegate(objectsDelegate = this, queue = dispatch_get_main_queue())` delivers every metadata callback on the main thread. Inside that callback (line 97), `captureSession?.stopRunning()` is called synchronously — AVFoundation documents this as a blocking call that waits for the session to fully drain. After it returns, `onQrDetected(qrData)` fires, which in the Compose layer (QRDiscoveryScreen.ios.kt lines 74–76) calls `manager.isHandshaking.value = true` and `establishTcpClient(qrData)` — all on the main thread. Session teardown plus Kotlin–Native bridge overhead on the main thread is a real frame-budget risk.
- **Fix:** Create a dedicated serial DispatchQueue (`dispatch_queue_create("qr.meta", DISPATCH_QUEUE_SERIAL)`) and pass it instead of `dispatch_get_main_queue()`. Parse the QR value and call `stopRunning()` on that queue. Then dispatch only the lightweight state update (`isHandshaking.value = true`) back to the main queue. This keeps the main thread free during session teardown.

#### 🟠 MED · rememberQrCodePainter with QrShapes/QrColors constructed outside remember — may re-encode QR on unrelated recompositions

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:185` · _Redundant recomputation_
- **Why it's slow:** At lines 185-191, rememberQrCodePainter receives QrShapes(darkPixel = QrPixelShape.roundCorners()) and QrColors(dark = QrBrush.solid(colorScheme.primary)) constructed inline without remember. If the qrose library keys its internal remember on these parameter objects (by reference equality), new object instances on every recomposition will cause a cache miss and re-encode the QR matrix. QR encoding via Reed-Solomon is computationally expensive. The qrData.toString() key is stable as long as QRData is a data class, but QrShapes/QrColors freshly allocated each time are not.
- **Fix:** Wrap QrShapes and QrColors in remember: val qrShapes = remember { QrShapes(darkPixel = QrPixelShape.roundCorners()) } and val qrColors = remember(colorScheme.primary) { QrColors(dark = QrBrush.solid(colorScheme.primary)) }. Also verify QRData is a data class so qrData.toString() is structurally stable.

#### 🟠 MED · QRData.toString() called without remember in rememberQrCodePainter — Android QrCodeBlock

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:186` · _Allocations / GC churn in hot path_
- **Why it's slow:** `qrData.toString()` at line 186 is called unconditionally on every composition of `QrCodeBlock`. `QRData.toString()` calls `qrEscape()` on each of the five text fields, each of which allocates a `StringBuilder` and copies every character. Since `QrCodeBlock` is driven by the same `infiniteTransition` as its `drawBehind`, it recomposes on every frame tick for the animated values — meaning this multi-allocation string build runs at animation frame rate.
- **Fix:** Cache the serialized string: `val qrString = remember(qrData) { qrData.toString() }` and pass `qrString` to `rememberQrCodePainter`. This limits the expensive string building and escaping to once per distinct QRData instance.

#### 🟠 MED · QrShapes and QrColors objects reconstructed on every recomposition without remember

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:187` · _Allocations / GC churn in hot path_
- **Why it's slow:** `QrShapes(darkPixel = QrPixelShape.roundCorners())` and `QrColors(dark = QrBrush.solid(colorScheme.primary))` at lines 187–190 are freshly allocated on every composition of `QrCodeBlock`. If `rememberQrCodePainter` uses referential equality to detect changes, new objects on every call will always be considered changed, potentially re-encoding the QR bitmap each frame. The same pattern exists in `QRDiscoveryScreen.desktop.kt` at lines 128–129, though with much lower recomposition frequency there.
- **Fix:** Hoist stable instances before the `rememberQrCodePainter` call: `val qrShapes = remember { QrShapes(darkPixel = QrPixelShape.roundCorners()) }` and `val qrColors = remember(colorScheme.primary) { QrColors(dark = QrBrush.solid(colorScheme.primary)) }`. Check whether `rememberQrCodePainter` uses structural or referential equality internally, as this determines whether the QR actually re-encodes.

#### 🟠 MED · Color.copy(alpha=…) allocations on every animation frame inside drawBehind — Android QrCodeBlock

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:205` · _Allocations / GC churn in hot path_
- **Why it's slow:** `QrCodeBlock.drawBehind` (lines 198–231) runs at 60+ fps driven by two `infiniteTransition.animateFloat` values. Line 205 calls `colorScheme.primary.copy(alpha = .7f)` with a constant alpha — this always produces the same Color and should be hoisted. Line 216 calls `colorScheme.primary.copy(alpha = cornerAlpha)` with an animated value. Separately, `QrLoadingBlock` at line 261 uses `.background(colorScheme.primaryContainer.copy(alpha = alpha))` where `alpha` is animated, causing a new Color allocation on every recomposition of that composable.
- **Fix:** Hoist the constant `.copy(alpha = .7f)` scan-line color: `val scanLineColor = remember(colorScheme.primary) { colorScheme.primary.copy(alpha = .7f) }` in `QrCodeBlock` before the `drawBehind`. For the animated corner color at line 216, pre-capture `val primaryColor = colorScheme.primary` outside the lambda. The `QrLoadingBlock` animated background is harder to avoid since `.background()` reads its argument at each recomposition; using `graphicsLayer { alpha = ... }` instead would avoid the Color allocation.

#### 🟠 MED · Stroke objects allocated on every draw frame in SpinnerCircle drawBehind (Android) and SearchingSpinner drawBehind (iOS)

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:293` · _Allocations / GC churn in hot path_
- **Why it's slow:** Android `SpinnerCircle.drawBehind` (lines 291–302) creates `Stroke(stroke)` at line 293 and `Stroke(stroke, cap = StrokeCap.Round)` at line 299 on every draw frame. The stroke width is derived from `2.dp.toPx()`, which is constant. iOS `SearchingSpinner.drawBehind` (lines 395–403) does the same with `Stroke(stroke)` at line 398 and `Stroke(stroke, cap = StrokeCap.Round)` at line 400. Both spinners are driven by an infinite 700ms rotation animation, so drawBehind fires at frame rate.
- **Fix:** Hoist the Stroke objects outside the drawBehind lambda using remember. In each composable, before the `drawBehind` call: `val stroke = 2.dp.toPx()` (or `1.5.dp.toPx()` for iOS), then `val fillStroke = remember { Stroke(stroke) }` and `val arcStroke = remember { Stroke(stroke, cap = StrokeCap.Round) }`. Note that `toPx()` is only valid inside a composable layout/draw scope, so the stroke values should be captured using `LocalDensity` or inside `BoxWithConstraints`; alternatively keep the `toPx()` call inside `drawBehind` but cache the resulting `Stroke` with `remember`.

#### 🟠 MED · peerColors() is a @Composable function called twice per peer per recomposition — redundant MaterialTheme reads and allocations

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:82` · _Compose recomposition problems_
- **Why it's slow:** peerColors(index) is called at line 380 inside RadarView's forEachIndexed and at line 211 inside the peer list's forEachIndexed, for each peer per recomposition. Each call reads MaterialTheme.colorScheme (a CompositionLocal) and allocates a new PeerColors data class. With n peers that is 2n PeerColors allocations per recomposition of PeerDiscoveryScreenUI.
- **Fix:** Compute all peer colors once per recomposition with remember(colorScheme) { peers.mapIndexed { i, _ -> peerColors(i) } } and pass the list down. Alternatively, make peerColors a non-@Composable pure function taking a ColorScheme parameter and call it inside a remember block.

#### 🟠 MED · LaunchedEffect key captures availablePeers.isEmpty() boolean — stale read after delay inside effect body

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:105` · _Coroutine/Flow misuse_
- **Why it's slow:** LaunchedEffect(availablePeers.isEmpty(), isDiscovering) at line 105 correctly uses a boolean key, so it only restarts on empty/non-empty transitions, not on every list identity change. However, line 109 reads availablePeers.isEmpty() after delay(6000) — this is a stale snapshot read. availablePeers was collected from the StateFlow at composition time; by the time the delay completes, new peers may have been added but the composable's recomposition happened under a different scope. The result is emptyLongEnough could be set to true even though the list is no longer empty at that moment.
- **Fix:** Replace with a single LaunchedEffect(Unit) containing snapshotFlow { availablePeers.isEmpty() && isDiscovering }.distinctUntilChanged().collectLatest { isEmpty -> if (isEmpty) { delay(6000); emptyLongEnough = availablePeers.isEmpty() } } to avoid both the stale read and the restart-on-toggle behavior.

#### 🟠 MED · viewModelScope.launch used inside DisposableEffect and LaunchedEffect — coroutines may outlive the composable or run concurrently

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:122` · _Resource leak / Coroutine misuse_
- **Why it's slow:** At line 115-118, LaunchedEffect(null) launches an inner coroutine on viewModelScope for startDiscoveryAndAdvertising — cancelling the LaunchedEffect (when the composable leaves) does not cancel that inner coroutine. At line 122-127, the DisposableEffect onDispose block launches stopDiscoveryAndAdvertising on viewModelScope — a coroutine on viewModelScope is not tied to the composable lifetime and may run after the ViewModel is cleared. If the screen is rapidly entered and left, multiple start/stop coroutines can be in flight simultaneously.
- **Fix:** For the LaunchedEffect at line 115: call the suspend function directly without the inner launch (LaunchedEffect already provides a CoroutineScope). For the DisposableEffect cleanup at line 122: either make stopDiscoveryAndAdvertising a non-suspend fire-and-forget call, or launch on a dedicated SupervisorJob scope that you cancel in the same onDispose block.

#### 🟠 MED · Peer list rendered with forEachIndexed in a Column instead of LazyColumn — no keys, full re-layout on any list change

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:207` · _Compose recomposition problems_
- **Why it's slow:** Lines 207-217 iterate availablePeers with forEachIndexed inside a regular Column with no keys. Every time the peer list changes (peer added, removed, or signal-strength updated), the entire Column is recomposed and every PeerRow is re-laid-out. There is no key mechanism to skip unchanged peers. Note: the candidate finding's claim about a verticalScroll conflict is wrong — the inner Column is not scrollable; only the outer Column has verticalScroll.
- **Fix:** Replace Column + forEachIndexed with LazyColumn { items(availablePeers, key = { it.id }) { peer -> PeerRow(...) } }. Because the screen already has an outer verticalScroll Column, the LazyColumn must be set to a fixed height or the outer scroll must be removed.

#### 🟠 MED · Unstable onClick lambda allocated fresh every recomposition in forEachIndexed — prevents PeerRow from being skipped

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:214` · _Compose recomposition problems_
- **Why it's slow:** The onClick lambda at line 214 is constructed inline in forEachIndexed: `{ selectedPeer = if (selectedPeer == peer) null else peer }`. It captures both peer (from the loop closure) and the MutableState write for selectedPeer. Because it is a new lambda object on every recomposition, PeerRow's onClick parameter changes every time, preventing the Compose compiler from marking PeerRow as skippable even if peer and isSelected are unchanged.
- **Fix:** Wrap with remember(peer.id) { { selectedPeer = if (selectedPeer == peer) null else peer } } or, better, move the toggle logic into the ViewModel and pass a stable reference. When migrating to LazyColumn with keys, this becomes less critical as Compose can skip items more aggressively.

#### 🟠 MED · isHandshaking collectAsState in outer composable scope — changes trigger full PeerDiscoveryScreenUI recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:278` · _Compose recomposition problems_
- **Why it's slow:** val isHandshaking by manager.isHandshaking.collectAsState() at line 278 is read in PeerDiscoveryScreenUI's composition scope. Any change to isHandshaking triggers a full recomposition of PeerDiscoveryScreenUI — including RadarView with its infinite animations, the entire peer list, and the connect button. The position of the collectAsState call (after the main Box/Column) does not limit the recomposition scope; all state reads in the same composable function share the same scope.
- **Fix:** Extract the handshake overlay into a separate composable HandshakeOverlay(manager: PeerDiscoveryP2PM) that reads isHandshaking internally, and place it as a sibling inside a parent Box alongside the main content. State reads scoped to HandshakeOverlay will not trigger recomposition of the main content.

#### 🟠 MED · listOf(0.35f, 0.65f, 1f) and listOf(4.sdp, ...) allocated inside drawBehind / forEachIndexed on every frame/recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:334` · _Allocation in per-frame draw path_
- **Why it's slow:** Two unnecessary list allocations are confirmed: (1) `listOf(0.35f, 0.65f, 1f)` at line 334 inside the drawBehind lambda that runs at ~60 fps. (2) `listOf(4.sdp, 7.sdp, 10.sdp, 13.sdp)` at line 511 inside SignalBars, which is called once per PeerRow recomposition. The first is a per-frame hot allocation; the second is per-recomposition but bounded.
- **Fix:** Hoist both to file-level private vals. The drawBehind list in particular must not allocate — it runs every frame. The SignalBars list can also be hoisted since .sdp values are computed at composition time but the list structure is constant.

#### 🟠 MED · Color.copy() allocations inside drawBehind — called per frame for static alpha values

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:334` · _Allocations in hot draw path_
- **Why it's slow:** In RadarView's drawBehind (lines 334-371), colorScheme.primary.copy(alpha=.15f) is called 3 times inside a forEach for rings, plus copy(alpha=.2f) for the sweep arc and copy(alpha=.5f) for the sweep line, all at constant alpha values that never change. On Android/JVM, Color is an inline Long class so copy() does not heap-allocate, but it still performs a float-to-bits conversion and Long construction each frame at ~60fps. The animated ones (centerPulse-driven copy at line 368, rippleAlpha-driven copy at line 418 per peer) must stay inside drawBehind.
- **Fix:** Hoist the five constant-alpha copies outside drawBehind using remember(colorScheme.primary) { colorScheme.primary.copy(alpha=0.15f) } etc. Only copies driven by animated floats (centerPulse, rippleAlpha) must remain inside the draw lambda. On Kotlin/JS targets Color does box, making this more urgent there.

#### 🟠 MED · Color.copy(alpha=…) allocates a new Color object on every animation frame inside drawBehind (iOS ViewfinderOverlay)

- **Where:** `shared/src/iosMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.ios.kt:281` · _Allocations / GC churn in hot path_
- **Why it's slow:** `ViewfinderOverlay.drawBehind` runs at up to 60 fps driven by two `infiniteTransition.animateFloat` values. On every frame it calls `colorScheme.primary.copy(alpha = cornerAlpha)` at line 281 (animated alpha, changes every frame) and `colorScheme.primary.copy(alpha = .8f)` at line 285 (constant alpha, always the same Color value). Both read `colorScheme` inside the draw lambda and allocate new Color objects. The constant-alpha variant at line 285 is the more wasteful one since it always produces the same result.
- **Fix:** Before the `drawBehind` block, capture: `val primaryColor = colorScheme.primary`. For the constant scan-line color, hoist it with `remember`: `val scanLineColor = remember(colorScheme.primary) { colorScheme.primary.copy(alpha = .8f) }`. For the animated corner color, keep the `copy(alpha = cornerAlpha)` call inside the lambda but apply it to the already-captured `primaryColor` rather than re-reading `colorScheme.primary` on every frame.

#### 🟠 MED · ViewfinderOverlay allocates two Color objects via colorScheme.primary.copy() on every draw frame

- **Where:** `shared/src/iosMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.ios.kt:281` · _GC churn in per-frame draw path_
- **Why it's slow:** Inside the drawBehind lambda of ViewfinderOverlay (lines 274-307), colorScheme.primary.copy(alpha = cornerAlpha) at line 281 and colorScheme.primary.copy(alpha = .8f) at line 285 are called on every draw frame. drawBehind runs every frame while the infiniteRepeatable animations are active (cornerAlpha and scanY are both animating). Color.copy() allocates a new Color object per call. At 60 fps this is 120 Color allocations per second while the QR scan screen is visible.
- **Fix:** Hoist the static .8f-alpha color outside drawBehind with remember { colorScheme.primary.copy(alpha = .8f) }. The cornerAlpha variant must vary per-frame, so pre-compute the base color once with remember { colorScheme.primary } and call .copy(alpha = cornerAlpha) inside the lambda — that reduces to one Color allocation per frame instead of two.

#### 🟠 MED · captureSession?.stopRunning() called without a debounce guard — allows redundant calls if session is already stopped

- **Where:** `shared/src/iosMain/kotlin/jetzy/uiviewcontroller/QRScannerController.kt:97` · _Busy-wait / redundant work on hot path_
- **Why it's slow:** `captureSession?.stopRunning()` at line 97 is called unconditionally before `firstOrNull()` guards against empty metadata lists. If `didOutputMetadataObjects` is empty, the function stops the session and returns without calling `onQrDetected` — a wasted session teardown. However, since the callback runs on the main queue (a serial queue), true concurrent re-entry is impossible. The more actionable concern (shared with Finding 1) is that `stopRunning()` blocks on the main thread. The duplicate-`onQrDetected` scenario described in the candidate finding does not apply because the main queue serializes all callbacks.
- **Fix:** Move `captureSession?.stopRunning()` after the early-return guard (`firstOrNull() ?: return`) so it only runs when a valid QR is present. Also add a `private var isProcessing = false` flag set before `stopRunning()` and checked at the top of the callback to prevent any edge-case re-entry from viewWillAppear restarting the session while processing. Address the main-thread blocking as described in Finding 1.

#### 🟡 LOW · Multiple qrData!! non-null assertions inside if(qrData != null) read MutableState repeatedly

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:137` · _Allocations / GC churn in hot path_
- **Why it's slow:** Lines 137–139 contain three `qrData!!` accesses inside `if (qrData != null)`. Since `qrData` is a `var` delegated to `mutableStateOf`, the compiler cannot smart-cast it, so each `!!` re-reads the state slot and emits a null-check. This is a minor inefficiency per composition pass, not a per-frame cost.
- **Fix:** Capture a local val before the branch: `val data = qrData; if (data != null) { QrCodeBlock(qrData = data); LivePill(ssid = data.hotspotSSID); CredentialsRow(qrData = data) }`. This reads the state slot once and smart-casts cleanly.

#### 🟡 LOW · QrCodeBlock toPx() conversions computed on every animation frame inside drawBehind

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:213` · _Allocations in hot draw path_
- **Why it's slow:** Inside QrCodeBlock's drawBehind at lines 213-216, cSize = 18.dp.toPx(), cStroke = 2.5.dp.toPx(), and margin = 4.dp.toPx() are computed every animation frame. toPx() reads the Density from DrawScope and performs a float multiply. These are constant for a given density. The animated color.copy(alpha = cornerAlpha) at line 216 must stay inside. The iOS ViewfinderOverlay (QRDiscoveryScreen.ios.kt lines 278-280) has the same pattern with cSize=22.dp, cStroke=2.5.dp, margin=10.dp.
- **Fix:** Use drawWithCache instead of drawBehind to cache the pixel values: onDrawBehind { /* use cached cSizePx, cStrokePx, marginPx */ }. drawWithCache recalculates only when size changes, which is the correct trigger for density-derived px values. Only the animated color value needs to be re-derived each frame.

#### 🟡 LOW · CredentialsRow builds masked password string with string concatenation on every recomposition

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:357` · _Allocations in hot composition path_
- **Why it's slow:** At line 357, qrData.hotspotPassword.take(8) + "••••" creates two String objects on every recomposition of CredentialsRow. qrData is set once from LaunchedEffect and rarely changes, so this is infrequent in practice. The fix is trivially correct and low risk.
- **Fix:** Wrap with remember(qrData.hotspotPassword) { qrData.hotspotPassword.take(8) + "••••" } to compute it once per password value.

#### 🟡 LOW · Password string concatenation allocates two intermediate Strings per CredentialsRow composition

- **Where:** `shared/src/androidMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.android.kt:357` · _Allocations / GC churn in hot path_
- **Why it's slow:** `qrData.hotspotPassword.take(8) + "••••"` at line 357 inside `CredentialsRow` allocates a substring via `take(8)` and a second String via `+` concatenation on every composition. `CredentialsRow` is called from `P2pQrContent` which can recompose due to the animated `LivePill` sibling. The result is constant relative to `qrData`, so it should be memoized.
- **Fix:** Hoist with `remember`: in `CredentialsRow`, `val maskedPw = remember(qrData.hotspotPassword) { qrData.hotspotPassword.take(8) + "••••" }` and pass `maskedPw` as the value argument to `CredCard`.

#### 🟡 LOW · peerColors() allocates a new PeerColors data class on every recomposition, and calls Color.copy() inside drawBehind per frame

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:82` · _Allocation in recomposition path_
- **Why it's slow:** peerColors(index) is a @Composable function (confirmed at line 81) that creates a new PeerColors data class without using remember(). It is called at composable-body time (line 380), not inside drawBehind — so it runs at recomposition, not per-frame. The original finding incorrectly claims it runs inside drawBehind. However, the captured colors.accent.copy(alpha = rippleAlpha) call at line 418 is inside drawBehind and IS per-frame — but that is finding 9's domain. The peerColors recomposition issue alone is low severity.
- **Fix:** Add `remember(index, colorScheme) { ... }` inside peerColors or pre-build a 3-element array of PeerColors outside any composable and index into it.

#### 🟡 LOW · LaunchedEffect(availablePeers.isEmpty(), isDiscovering) resets the 6-second timer on every emission where isEmpty() stays true

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:105` · _Coroutine misuse / redundant work_
- **Why it's slow:** LaunchedEffect(availablePeers.isEmpty(), isDiscovering) at line 105 uses a Boolean expression as a key. If the peers list changes while remaining empty (e.g., a peer appears then immediately disappears), the Boolean stays true but recomposition occurs — however, Compose LaunchedEffect only cancels and restarts when the key VALUE changes. If isEmpty() returns the same Boolean value on consecutive recompositions, the effect does NOT restart. The real risk is only if isEmpty() actually changes value, which is the correct semantic. The timer is reset on empty→nonempty→empty transitions, which is arguably correct behavior.
- **Fix:** No urgent action needed. The LaunchedEffect correctly restarts only when isEmpty() or isDiscovering changes value, not on every recomposition. If the UX requirement is 'first continuous 6s without peers', use a single LaunchedEffect(isDiscovering) and observe availablePeers as a flow inside it.

#### 🟡 LOW · peer.name.take(2).uppercase() string allocation inside PeerRow composition — called per recomposition per peer

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:477` · _Allocations in hot composition path_
- **Why it's slow:** Line 477 computes peer.name.take(2).uppercase() on every recomposition of PeerRow, creating two intermediate String objects per peer per recomposition. This is minor but unnecessary since peer.name rarely changes.
- **Fix:** Wrap with remember(peer.name) { peer.name.take(2).uppercase() } to cache the result for a given peer name.

#### 🟡 LOW · SignalBars allocates a List of sdp-scaled Dp values on every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/PeerDiscoveryScreen.kt:511` · _Allocations in hot composition path_
- **Why it's slow:** SignalBars at line 511 calls listOf(4.sdp, 7.sdp, 10.sdp, 13.sdp) on every recomposition. The .sdp extension is a @Composable getter that reads LocalDensity and LocalWindowInfo to compute a scale factor, so it cannot be hoisted to a top-level val. However, the scale factor changes only when the window/density changes, so the list can be memoized with remember(scaleFactor). The candidate's suggestion of a top-level val is wrong because sdp is density-dependent, but remember is the correct fix.
- **Fix:** Capture the scale factor once: val scale = getScaleFactor() then val barHeights = remember(scale) { listOf(4 * scale, 7 * scale, 10 * scale, 13 * scale).map { it.dp } }. This allocates the list once per density change rather than every recomposition.

#### 🟡 LOW · Color.Black.copy(alpha=0.8f) computed on every recomposition — trivial cost on JVM, slightly overstated severity

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.kt:40` · _Allocations in hot composition path_
- **Why it's slow:** val loadingOverlayColor = Color.Black.copy(alpha = 0.8f) is computed without remember in both PeerDiscoveryScreen.kt (line 280) and QRDiscoveryScreen.kt (line 40). On JVM/Android, Color is an inline Long class so copy() does not allocate on the heap. The cost is one float-to-bits computation per recomposition. This is only relevant on Kotlin/JS where Color boxes, or if contentColorFor(loadingOverlayColor) causes observable overhead. The finding is technically correct but the severity is low.
- **Fix:** Hoist to a file-level constant: private val OVERLAY_COLOR = Color.Black.copy(alpha = 0.8f). This is a pure constant with no platform dependency so a top-level val is correct here (unlike sdp-scaled values).

#### 🟡 LOW · QRData.toString() and string template called without remember in desktop HostQrPanel

- **Where:** `shared/src/desktopMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.desktop.kt:127` · _Allocations / GC churn in hot path_
- **Why it's slow:** `data.toString()` at line 127 and `"${data.ipAddress}:${data.port}"` at line 140 are called without `remember` wrapping. However the desktop `HostQrPanel` has no animation — there is no `infiniteTransition` or animated state. Recomposition only happens on external triggers (theme change, window resize, `qrData` state change). The cost is real but the frequency is very low in practice.
- **Fix:** Wrap both expressions in `remember(data)`: `val qrString = remember(data) { data.toString() }` and `val address = remember(data) { "${data.ipAddress}:${data.port}" }`. Low-effort defensive improvement.

#### 🟡 LOW · SignalBars duplicated in iOS file as dead code — NearbyDeviceRow is never called

- **Where:** `shared/src/iosMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.ios.kt:368` · _Allocations in hot composition path_
- **Why it's slow:** QRDiscoveryScreen.ios.kt defines NearbyDeviceRow (line 312) and a private SignalBars (line 366) that it calls. NearbyDeviceRow is never invoked from P2pQrContent or AutoJoinRaceDialog — it is dead code. The SignalBars composable is therefore also unreachable. The per-recomposition list allocation in the iOS SignalBars is a real pattern but it cannot execute since the caller is dead. The binary-size and maintenance overhead are the real costs.
- **Fix:** Delete NearbyDeviceRow and the iOS-private SignalBars from QRDiscoveryScreen.ios.kt. If signal-strength display is needed in the iOS QR flow in the future, share the SignalBars from PeerDiscoveryScreen.kt after making it internal.

#### 🟡 LOW · Stroke objects allocated inside drawBehind on every frame in SearchingSpinner and NearbyDeviceRow

- **Where:** `shared/src/iosMain/kotlin/jetzy/ui/discovery/QRDiscoveryScreen.ios.kt:398` · _GC churn in per-frame draw path_
- **Why it's slow:** In SearchingSpinner at lines 398 and 400, Stroke(stroke) and Stroke(stroke, cap = StrokeCap.Round) are allocated inside the drawBehind lambda that executes every frame (rotation is an infiniteRepeatable animation). In NearbyDeviceRow at line 343, Stroke(width = 1.5.dp.toPx()) is similarly allocated inside drawBehind. NearbyDeviceRow is not animated so its drawBehind runs only on invalidation — not per-frame. SearchingSpinner is animated and its Stroke allocations are per-frame at 60 fps.
- **Fix:** For SearchingSpinner, pre-compute the two Stroke objects with remember in the Composable scope. For NearbyDeviceRow the drawBehind is only recomposition-driven (not animation-driven), so the allocation frequency is low and the fix is cosmetic.

### UI · Main / shared — 23 (2H / 8M / 13L)

#### 🔴 HIGH · derivedStateOf without remember in platforms forEach loop

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/MainScreen.kt:200` · _Compose recomposition_
- **Why it's slow:** `val isSelected by derivedStateOf { platform == peerPlatform }` at line 200, inside the `platforms.forEach` block, has no `remember` wrapper. This loop runs 2-3 times per recomposition of the platform-selection section, creating multiple orphaned `DerivedState`/snapshot observer objects per frame. The platform-selection section recomposes on every `peerPlatform` state change.
- **Fix:** Use `remember(platform) { derivedStateOf { platform == peerPlatform } }` for each iteration so the observer is stable across recompositions of the same platform slot.

#### 🔴 HIGH · derivedStateOf without remember creates a new observer each recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/OperationButton.kt:44` · _Compose recomposition_
- **Why it's slow:** `val isSelected by derivedStateOf { currentOperation == operation }` at line 44 has no `remember` wrapper. Compose allocates a fresh `DerivedState` object and registers a new snapshot observer on every recomposition. `OperationButton` is called twice (SEND and RECEIVE) and recomposes on every `currentOperation` change. The old observer is abandoned before a new one is created, causing unnecessary allocations and preventing Compose from skipping recomposition of the button body when `isSelected` has not changed.
- **Fix:** Wrap with `remember`: `val isSelected by remember { derivedStateOf { currentOperation == operation } }`.

#### 🟠 MED · Two collectAsState() calls inside a conditional title slot recomposes unnecessarily

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/AdamScreen.kt:129` · _Coroutine/Flow misuse / Compose recomposition_
- **Why it's slow:** Inside the `title = { ... }` lambda, `collectAsState()` is called conditionally at lines 129-130 only when `currentScreen !is Screen.MainScreen`. Compose rules prohibit conditional `remember`/`collectAsState` calls because the number of remembered slots must be stable across recompositions. When `currentScreen` flips between `MainScreen` and another screen, these subscriptions are torn down and recreated, causing a momentary blank title and unnecessary Flow subscription churn. Both `op` and `prp` state changes also force the entire `CenterAlignedTopAppBar` title slot to recompose, which is broader than necessary.
- **Fix:** Collect `currentOperation` and `currentPeerPlatform` at the top of `AdamScreen` unconditionally alongside `currentScreen`, then pass derived display values down to the title lambda. This stabilizes subscriptions and avoids the conditional `collectAsState` anti-pattern.

#### 🟠 MED · collectAsState called on currentOperation and currentPeerPlatform inside Scaffold topBar lambda in AdamScreen

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/AdamScreen.kt:129` · _Compose recomposition — state read too high in the tree forcing wide recomposition_
- **Why it's slow:** Inside the `title = { ... }` slot of the `CenterAlignedTopAppBar`, `val op by viewmodel.currentOperation.collectAsState()` and `val prp by viewmodel.currentPeerPlatform.collectAsState()` are called at lines 129–130. These create additional coroutine-based state subscriptions beyond the ones already established at the `AdamScreen` level (lines 98, 103, etc.). Every emission from these flows now triggers recomposition of the `topBar` slot's entire subtree as a second subscription, independently of the outer scope.
- **Fix:** Hoist `op` and `prp` outside the `topBar` lambda by collecting them at the `AdamScreen` composable body level once, then capturing them by reference inside the slot lambda. This reduces the subscription count from 2 to 1 per flow and avoids the redundant recomposition scope.

#### 🟠 MED · platforms buildList rebuilt on every recomposition without remember

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/MainScreen.kt:193` · _Compose recomposition / GC churn_
- **Why it's slow:** `val platforms = buildList { add(Platform.Android); add(Platform.IOS); if (platform != Platform.IOS) add(Platform.PC) }` at line 193 has no `remember`. This list is rebuilt on every recomposition of `MainScreenUI`, which recomposes on every `peerPlatform` state change. The content depends only on the compile-time `platform` expect value, which never changes at runtime, making every rebuild identical and wasteful.
- **Fix:** Hoist to `val platforms = remember { buildList { add(Platform.Android); add(Platform.IOS); if (platform != Platform.IOS) add(Platform.PC) } }` since `platform` is a compile-time constant.

#### 🟠 MED · derivedStateOf created inside forEach loop per platform in MainScreenUI

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/MainScreen.kt:200` · _Compose recomposition — missing remember/derivedStateOf_
- **Why it's slow:** `val isSelected by derivedStateOf { platform == peerPlatform }` is called at line 200 inside `platforms.forEach { }` without a `key {}` or `remember`. Compose cannot associate the `DerivedState` with a stable call-site; on every recomposition a new `DerivedState` is created, registered with the snapshot system, and immediately orphaned. This defeats the purpose of `derivedStateOf` entirely — it should prevent recomposition but instead causes extra snapshot-system churn.
- **Fix:** Either remove `derivedStateOf` and read `peerPlatform` directly as `isSelected = platform == peerPlatform` (since `peerPlatform` is already observed via `collectAsState` at the top of `MainScreenUI`), or wrap with `key(platform) { val isSelected by remember { derivedStateOf { platform == peerPlatform } }; VerticalCardButton(...) }`.

#### 🟠 MED · ToggleButtonDefaults.shapes() with three new RoundedCornerShape objects allocated per recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/OperationButton.kt:50` · _Compose recomposition / GC churn_
- **Why it's slow:** `ToggleButtonDefaults.shapes(shape = RoundedCornerShape(15), pressedShape = RoundedCornerShape(30), checkedShape = RoundedCornerShape(40))` at lines 50-52 allocates three `RoundedCornerShape` objects and a `SelectableButtonShapes` wrapper on every recomposition of `OperationButton`. This composable is called twice (SEND and RECEIVE) and recomposes on every `currentOperation` state change. The shapes are constant and never need to change.
- **Fix:** Hoist to a file-level val or wrap in `remember`: `val buttonShapes = remember { ToggleButtonDefaults.shapes(RoundedCornerShape(15), RoundedCornerShape(30), RoundedCornerShape(40)) }` at the top of `OperationButton`.

#### 🟠 MED · structuralKey String allocated via joinToString on every dialog recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:76` · _Allocation in composition_
- **Why it's slow:** `val structuralKey = requirements.joinToString("|") { it.id }` runs unconditionally in the composable body (line 76) with no `remember`. The dialog recomposes at least every 400ms when `produceState` emits a new `grantedFlags` value, so a new String is concatenated every poll tick. The comment above the line (lines 73-75) explicitly acknowledges that `requirements` is a fresh list on every parent recomposition, but still doesn't apply `remember` to the key computation itself.
- **Fix:** Wrap in `remember`: `val structuralKey = remember(requirements) { requirements.joinToString("|") { it.id } }`. If the requirements list is stabilised (see prior finding), the key becomes a constant and `remember` with an empty key suffices.

#### 🟠 MED · Permission polling allocates a new List<Boolean> (with boxed Boolean) every 400 ms

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:83` · _GC churn / tight polling_
- **Why it's slow:** The `produceState` coroutine at lines 83 and 86 calls `requirements.map { it.isGrantedNow() }` every 400ms. `List.map` allocates a new `ArrayList` and boxes each `Boolean` into a heap `Boolean` object. The list is immediately discarded, creating steady minor-GC pressure for the dialog's lifetime. `isGrantedNow()` may invoke system-service calls (Wi-Fi state, permission checks) on the coroutine that could be better dispatched to `Dispatchers.Default`. Setting `value` unconditionally on every poll tick also triggers spurious recompositions even when grant status has not changed.
- **Fix:** Use a `BooleanArray` and set `value` only when the result differs: compute `val next = BooleanArray(requirements.size) { requirements[it].isGrantedNow() }`, then update `value` only if it changed. Consider using `Dispatchers.Default` for `isGrantedNow()` calls.

#### 🟠 MED · FontFamily(Font()) allocated on every recomposition in VerticalCardButton

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/VerticalCardButton.kt:83` · _Compose recomposition / GC churn_
- **Why it's slow:** `fontFamily = FontFamily(Font(Res.font.genos))` at line 83 is called bare in the composition body of `VerticalCardButton`. `FontFamily(Font(...))` allocates a new `FontFamily` wrapper object on every recomposition. `VerticalCardButton` is called 2-3 times per platform row and recomposes whenever `isSelected` changes. The claim about `OperationButton` is also correct (line 79 of OperationButton.kt has the same pattern). Severity is medium rather than high because font resource loading is typically memoized at a lower framework layer, so the main cost is wrapper object allocation rather than actual font loading.
- **Fix:** Cache with `remember`: `val genosFamily = remember { FontFamily(Font(Res.font.genos)) }` at the top of each composable.

#### 🟡 LOW · String interpolation "$s1 " allocates a new String in the title slot on every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/AdamScreen.kt:139` · _Compose recomposition / GC churn_
- **Why it's slow:** `Text("$s1 ", ...)` at line 139 appends a trailing space via string interpolation, allocating a new `String` on every recomposition of the title slot. The trailing space is a layout workaround. The allocation is trivial — one short-lived `String` per title recomposition, which only happens when `currentOperation` or `currentPeerPlatform` changes. This is a negligible cost in practice.
- **Fix:** Replace with `Text(s1, modifier = Modifier.padding(end = 4.sdp))` and use explicit padding instead of the trailing space, eliminating the unnecessary `StringBuilder` allocation.

#### 🟡 LOW · buildList { } for platforms list re-allocated every recomposition of MainScreenUI

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/MainScreen.kt:193` · _Compose recomposition — building lists in composition_
- **Why it's slow:** `buildList { add(Platform.Android); add(Platform.IOS); if (platform != Platform.IOS) add(Platform.PC) }` is called at line 193 directly in composition. `platform` is a compile-time per-target singleton, so this list is structurally constant at runtime. A new `ArrayList` is allocated on every recomposition of `MainScreenUI` (triggered by `operation`, `peerPlatform`, or file-count changes).
- **Fix:** Hoist to a file-level `private val PEER_PLATFORMS = buildList { add(Platform.Android); add(Platform.IOS); if (platform != Platform.IOS) add(Platform.PC) }` since `platform` is a compile-time constant. This eliminates all per-recomposition allocation.

#### 🟡 LOW · derivedStateOf in OperationButton inside the composable body without remember

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/OperationButton.kt:44` · _Compose recomposition — allocating in composition without remember_
- **Why it's slow:** derivedStateOf { currentOperation == operation } on line 44 is called without being wrapped in `remember {}`. On each recomposition Compose creates a new derivedState object, reads it, and discards it — negating the purpose of derivedStateOf entirely. The collectAsState() on line 43 already gives minimal recompositions; the unwrapped derivedStateOf adds object allocation with no benefit.
- **Fix:** Either remove derivedStateOf entirely (use `val isSelected = currentOperation == operation` directly since collectAsState() already provides stable recomposition), or wrap it: `val isSelected by remember { derivedStateOf { currentOperation == operation } }`.

#### 🟡 LOW · structuralKey joinToString runs on every recomposition without remember

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:76` · _Compose recomposition / GC churn_
- **Why it's slow:** `val structuralKey = requirements.joinToString("|") { it.id }` at line 76 is computed on every recomposition of `PermissionGateDialog`. The dialog recomposes every 400ms due to the `produceState` polling loop. Each recomposition allocates a `StringBuilder` and concatenates all requirement IDs, discarding the result. The actual severity is low: the string is short (a few requirement IDs joined with `|`), `joinToString` on a small list is inexpensive, and the dialog only appears briefly during transfer setup. The comment in the code correctly documents why the key exists — the real cost is proportional to requirement count, which is small.
- **Fix:** Wrap in `remember(*requirements.map { it.id }.toTypedArray()) { requirements.joinToString("|") { it.id } }` if the requirement list is expected to grow, otherwise the cost is negligible.

#### 🟡 LOW · requirements.joinToString called in composition body without remember in PermissionGateDialog

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:76` · _Compose recomposition — heavy/allocating work inside composition_
- **Why it's slow:** `val structuralKey = requirements.joinToString("|") { it.id }` at line 76 is called on every recomposition of `PermissionGateDialog`. The comment notes that `requirements` is rebuilt on every parent recomposition. `joinToString` allocates a `StringBuilder` and produces a result `String`. The dialog recomposes every 400ms from the `produceState` poll. However, `structuralKey` is immediately passed as `key1` to `produceState` — if it changes every recomposition (because `requirements` is a new list reference even if contents are identical), the producer would restart. The `joinToString` on `id` fields correctly produces a content-stable key even when the list reference changes.
- **Fix:** Wrap in `remember(requirements.size)` as a fast-path guard: `val structuralKey = remember(requirements.size) { requirements.joinToString("|") { it.id } }`. If requirement IDs can change without size change, use a stable key passed from the call site instead.

#### 🟡 LOW · grantedFlags mapped to List<Boolean> — boxed Boolean prevents primitive-array optimization

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:78` · _GC churn / allocations_
- **Why it's slow:** `requirements.map { it.isGrantedNow() }` at lines 78, 83, and 86 produces a `List<Boolean>` where each element is a heap-allocated boxed `Boolean`. This is a duplicate concern with finding #6 (permission polling); both describe the same three call sites. The boxing overhead is real but minimal given that permission lists typically contain 2-5 elements. The main cost is the `ArrayList` allocation per poll cycle, not the per-element boxing.
- **Fix:** Use `BooleanArray(requirements.size) { requirements[it].isGrantedNow() }` and update all consumption sites (`all { }`, `getOrElse`) accordingly. This is a combined fix with the polling finding.

#### 🟡 LOW · produceState polling loop allocates new List<Boolean> every 400ms via requirements.map

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:83` · _Compose recomposition — collectAsState on hot flows without conflation / repeated allocation_
- **Why it's slow:** The `produceState` producer at lines 83–87 calls `requirements.map { it.isGrantedNow() }` every 400ms in a `while(true)` loop. Each `.map` allocates a new `ArrayList` wrapping N `Boolean` values. For permission checks, `isGrantedNow()` likely calls a system API (e.g., `ContextCompat.checkSelfPermission`). With N requirements this is N system calls + 1 list allocation every 400ms. The allocation rate is low and bounded; the permission system API cost is the dominant concern.
- **Fix:** Use `BooleanArray(requirements.size) { requirements[it].isGrantedNow() }` instead of `requirements.map { it.isGrantedNow() }` to avoid boxing N `Boolean` objects. Also only assign `value =` when the result differs from the current value to avoid triggering unnecessary recompositions: `val next = BooleanArray(requirements.size) { requirements[it].isGrantedNow() }; if (next.toList() != value) value = next.toList()` (or keep as `BooleanArray` and update the state type accordingly).

#### 🟡 LOW · allGranted computed inline without derivedStateOf causes per-recomposition lambda allocation

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:89` · _Compose recomposition — missing derivedStateOf_
- **Why it's slow:** `val allGranted = grantedFlags.size == requirements.size && grantedFlags.all { it }` is computed inline every recomposition (line 89). `grantedFlags.all { it }` allocates a lambda predicate each call. `LaunchedEffect(allGranted)` does key equality correctly — the effect only restarts when the boolean value flips, not every recomposition — so the claim of unnecessary LaunchedEffect restarts is incorrect. The real cost is just the repeated lambda allocation from `all { it }` every 400ms, which is minor.
- **Fix:** Use `val allGranted by remember { derivedStateOf { grantedFlags.size == requirements.size && grantedFlags.all { it } } }` to avoid allocating the `all { it }` lambda predicate on every recomposition. The benefit is modest given the 400ms polling interval.

#### 🟡 LOW · Brush.verticalGradient and its color List allocated on every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:163` · _Compose recomposition / GC churn_
- **Why it's slow:** `Brush.verticalGradient(colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface))` at approximately line 163 allocates a new `List<Color>` and `LinearGradient` object on every recomposition. However, this code is inside `if (listScrollState.canScrollForward)`, so it only executes when the requirement list overflows the height cap. For most sessions with a small number of requirements, this branch is never taken. Even when taken, the 400ms polling is the real driver. The severity is low because this is a conditional branch rarely exercised.
- **Fix:** `val fadeBrush = remember(MaterialTheme.colorScheme.surface) { Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)) }` hoisted before the `Box` (still inside the condition guard).

#### 🟡 LOW · Brush.verticalGradient list allocated in composition without remember in PermissionGateDialog scroll-fade

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:163` · _Compose recomposition — building lists/Modifiers in composition_
- **Why it's slow:** `Brush.verticalGradient(colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface))` at lines 163–168 is constructed without `remember` inside the `if (listScrollState.canScrollForward)` branch. Every recomposition of `PermissionGateDialog` that enters this branch allocates a new `List<Color>` and a new `LinearGradient` `Brush` object. Recompositions occur every 400ms from the polling loop plus on any scroll state change.
- **Fix:** Cache with `remember(colorScheme.surface)`: `val fadeBrush = remember(colorScheme.surface) { Brush.verticalGradient(listOf(Color.Transparent, colorScheme.surface)) }` and pass `fadeBrush` to `Modifier.background(...)`. The `Brush` is then only rebuilt on theme change.

#### 🟡 LOW · PaddingValues allocated bare in PermissionGateDialog composition on every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/PermissionGateDialog.kt:301` · _Compose recomposition / GC churn_
- **Why it's slow:** `contentPadding = PaddingValues(horizontal = 10.sdp, vertical = 3.sdp)` at line 301 is inside `RequirementRow`, which recomposes every 400ms due to the polling loop. `PaddingValues` is a data class allocation. However, `PaddingValues` in Compose is implemented as a value class on some targets and a data class on others, and the compiler can optimize many such allocations away. The cost per recompose is one small heap object per requirement row, every 400ms. This is real but among the lowest-impact issues in this file — the `List<Boolean>` allocation from the polling (finding #6) is more significant.
- **Fix:** `val actionPadding = remember { PaddingValues(horizontal = 10.sdp, vertical = 3.sdp) }` at the top of `RequirementRow`.

#### 🟡 LOW · RoundedCornerShape allocated with dynamic sdp value on every recomposition in VerticalCardButton

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/VerticalCardButton.kt:51` · _Compose recomposition / GC churn_
- **Why it's slow:** `shape = RoundedCornerShape(if (isSelected) 10.sdp else 5.sdp)` at line 51 allocates a new `RoundedCornerShape` on every recomposition. Only two possible shapes exist. The sdp extension converts dp to a scaled pixel value; once the scale factor is memoized, the Dp values are constant. The suggestion to cache both variants is valid but the cost is a single small object per recompose of an already-cheap composable.
- **Fix:** Cache both: `val selectedShape = remember { RoundedCornerShape(10.sdp) }; val unselectedShape = remember { RoundedCornerShape(5.sdp) }`; then `shape = if (isSelected) selectedShape else unselectedShape`.

#### 🟡 LOW · TextAutoSize.StepBased object allocated per recomposition in VerticalCardButton

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/main/VerticalCardButton.kt:82` · _Compose recomposition / GC churn_
- **Why it's slow:** `autoSize = TextAutoSize.StepBased(minFontSize = 1.sp, maxFontSize = 25.sp)` at line 82 constructs a new `StepBased` object on every recomposition. `VerticalCardButton` is called 2-3 times per platform row and recomposes on selection changes. The arguments `1.sp` and `25.sp` are constant. This is a minor allocation but trivially fixable.
- **Fix:** Hoist to a file-level `val PLATFORM_AUTOSIZE = TextAutoSize.StepBased(minFontSize = 1.sp, maxFontSize = 25.sp)` outside the composable, since `sp` values are stable compile-time constants.

### UI · File picking — 15 (2H / 3M / 10L)

#### 🔴 HIGH · derivedStateOf wrapping a constant list allocation in ElementPickingScreen

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/ElementPickingScreen.kt:30` · _Compose recomposition / allocation_
- **Why it's slow:** `val sendScreens by derivedStateOf { buildList { add(Screen.PickFilesSubscreen) × 4 } }` reads no snapshot state whatsoever — the four Screen values are compile-time constants. `derivedStateOf` only memoises relative to other snapshot reads inside its block; with no reads the block re-executes on every recomposition, allocating a new list each time and boxing it in a `State` object. The `pagerState = rememberPagerState { sendScreens.size }` call means this runs on every recomposition of `ElementPickingScreen`.
- **Fix:** Hoist to a file-level `val sendScreens = listOf(Screen.PickFilesSubscreen, Screen.PickPhotosSubscreen, Screen.PickVideosSubscreen, Screen.PickTextSubscreen)`. Remove the `derivedStateOf` entirely.

#### 🔴 HIGH · Plain isHighlighted read without derivedStateOf — full cell recompose on every highlight change in PickPhotosSubscreen

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickPhotosSubscreen.kt:121` · _Compose recomposition_
- **Why it's slow:** `val isHighlighted = longclickedPhotos.contains(i)` at line 121 reads a `SnapshotStateList` directly inside the `itemsIndexed` lambda with no `derivedStateOf` wrapper. Every mutation to `longclickedPhotos` invalidates every visible cell's scope, causing all N cells to recompose and each perform an O(n) `.contains()` scan. This is the hottest composition path in this screen. Compare with `PickFilesSubscreen` which correctly wraps in `derivedStateOf`.
- **Fix:** At minimum wrap in `val isHighlighted by remember(i) { derivedStateOf { longclickedPhotos.contains(i) } }`. Better: switch to an O(1) `SnapshotStateMap<Int, Unit>` to eliminate the scan entirely.

#### 🟠 MED · derivedStateOf used for a linear SnapshotStateList.contains() scan inside itemsIndexed — O(n) per item per frame

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickFilesSubscreen.kt:237` · _Algorithmic inefficiency / Compose recomposition_
- **Why it's slow:** `val isHighlighted by derivedStateOf { highlightedItems.contains(i) }` inside `itemsIndexed`. `SnapshotStateList.contains()` is O(n) in the number of highlighted items. `derivedStateOf` does correctly scope recomposition to cells whose result changes, which is useful — but it allocates a `DerivedState` object per cell and still does an O(n) scan on every snapshot write. With m grid cells and n highlighted items, each highlight toggle triggers O(n×m) total work.
- **Fix:** Switch `highlightedItems` from `SnapshotStateList<Int>` to a `SnapshotStateMap<Int, Boolean>` or a `mutableStateOf<Set<Int>>`. Then the check is O(1) and `derivedStateOf` is unnecessary.

#### 🟠 MED · LaunchedEffect with fixed delay(300) for forced recomposition — per-cell coroutine on first render

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickPhotosSubscreen.kt:127` · _Busy-wait / polling_
- **Why it's slow:** Lines 127-132: each photo cell spawns `LaunchedEffect(i)` that `delay(300)` then increments `recomposePlease`. For N photos, N coroutines launch simultaneously at first render. `LaunchedEffect(i)` fires once per unique `i` value per entry into composition, so on initial render all N cells launch; on scroll, newly-entering cells each add another coroutine. This creates a wave of main-thread wakeups 300 ms after each render. The comment acknowledges this is a workaround for a FileKit/Coil bug.
- **Fix:** Fix the root Coil image-loading issue (likely a missing `size` constraint or `ImageLoader` config). If the workaround is unavoidable, apply it once at the `ImageLoader` level rather than N times per grid. Alternatively, an `onState` callback on `AsyncImagePainter` that detects `Error` or stalled `Loading` is more precise.

#### 🟠 MED · Missing key in itemsIndexed for PickVideosSubscreen — incorrect item identity on removals

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickVideosSubscreen.kt:88` · _Compose recomposition_
- **Why it's slow:** `itemsIndexed(videosForSending) { index, video -> ... }` at line 88 provides no `key` lambda. When an item is removed from the middle of the list, all subsequent items shift index, get new positional identity, and fully recompose. `PickTextSubscreen` has the same issue at line 110, and additionally has `var expanded by remember { mutableStateOf(false) }` inside the lambda — this `remember` state is keyed by position and will be lost for all items after the removed one.
- **Fix:** Add `key = { _, v -> v.video.name }` for videos and `key = { _, t -> t.text }` (or a stable UUID on the element) for texts.

#### 🟡 LOW · O(n×m) nested identity scan inside the FAB 'exclude' onClick handler

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickFilesSubscreen.kt:196` · _Algorithmic inefficiency_
- **Why it's slow:** `viewmodel.elementsToSend.removeAll { el -> toRemove.any { it === el } }` is O(n×m) where n is `toRemove.size` and m is `elementsToSend.size`. This runs on a button click (cold path), not per-frame. Stalling the UI requires a very large send list and many highlighted items simultaneously, which is an unlikely worst case for a file-sharing app.
- **Fix:** Pre-build `val toRemoveSet = toRemove.toHashSet()` using identity (via `IdentityHashMap` or `System.identityHashCode`) then `removeAll { el -> el in toRemoveSet }` for O(m) total.

#### 🟡 LOW · onGloballyPositioned on LazyVerticalGrid in PickFilesSubscreen — spurious recomposition risk on layout pass

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickFilesSubscreen.kt:232` · _Compose recomposition / main thread work_
- **Why it's slow:** Identical pattern to PickPhotosSubscreen: `onGloballyPositioned { cellWidth = with(density) { it.size.width.toDp() / 4 } }` at lines 232-234. Same analysis — after first stable layout the write is idempotent, but the callback/state-write mechanism is still unnecessary overhead during initial layout and resize.
- **Fix:** Use `BoxWithConstraints { val cellWidth = maxWidth / 4 }` in composition.

#### 🟡 LOW · Linear SnapshotStateList.contains() and .remove() inside long-click handler — O(n) on the UI thread

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickFilesSubscreen.kt:249` · _Algorithmic inefficiency_
- **Why it's slow:** The `onLongClick` handler at line 248 calls `highlightedItems.contains(i)` and `highlightedItems.remove(i)`, both O(n). In practice the highlight list stays very small (a handful of user-selected items), so the real-world cost is negligible. The pattern is still worth fixing for correctness and consistency with a map-based approach.
- **Fix:** Use a `mutableStateOf<Set<Int>>` and toggle with set copy operations. This also makes `derivedStateOf` on the read side redundant.

#### 🟡 LOW · Color.copy(alpha = ...) allocation inside Modifier.border() called per-cell per-recompose

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickFilesSubscreen.kt:257` · _Allocation / GC churn in hot path_
- **Why it's slow:** `scheme.onSurface.copy(alpha = 0.05f)` at line 257 inside `itemsIndexed` allocates a `Color` value (a `ULong`-backed inline class, but still a new boxed value when passed through non-inline `Modifier.border`) on every cell recompose. `scheme` does not change between recompositions in stable lighting conditions.
- **Fix:** Cache outside the `itemsIndexed` lambda: `val borderColor = remember(scheme.onSurface) { scheme.onSurface.copy(alpha = 0.05f) }` at the top of `FileFolderGridView`, then reference `borderColor` inside the grid.

#### 🟡 LOW · Double smart-cast in FileFolderGridView item label — redundant cast on every cell recompose

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickFilesSubscreen.kt:269` · _Allocation / GC churn in hot path_
- **Why it's slow:** `(f as? JetzyElement.File)?.file?.name ?: (f as? JetzyElement.Folder)?.folder?.name ?: ""` at line 269. The `allItems` list is either entirely `JetzyElement.File` or entirely `JetzyElement.Folder` (determined by `viewMode`), so one of the two casts always fails and is wasted. In Files mode the second cast is always null; in Folders mode the first cast is always null. Both execute on every cell recompose.
- **Fix:** `when (viewMode) { FileFolderViewMode.Files -> (f as JetzyElement.File).file.name; FileFolderViewMode.Folders -> (f as JetzyElement.Folder).folder.name }` — single known cast per branch, predictable by the VM's inline cache.

#### 🟡 LOW · onGloballyPositioned fires on every layout pass and triggers recomposition via mutableStateOf write in PickPhotosSubscreen

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickPhotosSubscreen.kt:115` · _Compose recomposition / main thread work_
- **Why it's slow:** `onGloballyPositioned { cellWidth = with(density) { it.size.width.toDp() / 3 } }` at lines 115-116 writes `cellWidth` (a `mutableStateOf<Dp>`) on every layout pass of the `LazyVerticalGrid`. Scrolling does not trigger this (scroll is not a layout pass for the parent). However, initial layout and window/orientation changes do. Since `Dp` equality uses float comparison and the value is deterministic from stable pixel width, after the first layout the write is idempotent and Compose will not schedule recomposition (same-value write is a no-op for `mutableStateOf`). The real concern is the brief startup period before size stabilises.
- **Fix:** Use `BoxWithConstraints { val cellWidth = maxWidth / 3 }` to compute `cellWidth` once in composition without a layout callback.

#### 🟡 LOW · String key `"$i $recomposePlease"` allocated on every recomposition of each cell

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickPhotosSubscreen.kt:134` · _Allocation / GC churn in hot path_
- **Why it's slow:** `key("$i $recomposePlease") { ... }` at line 134 concatenates two integers into a new `String` on each cell recomposition. `key()` accepts `Any` vararg, so passing the integers directly avoids the string allocation.
- **Fix:** Change to `key(i, recomposePlease) { AsyncImage(...) }`.

#### 🟡 LOW · ColorFilter.tint(...) with jetzyYellow.copy(alpha=0.75f) allocated inside itemsIndexed lambda per recompose

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickPhotosSubscreen.kt:139` · _Allocation / GC churn in hot path_
- **Why it's slow:** `if (isHighlighted) ColorFilter.tint(jetzyYellow.copy(alpha = 0.75f), blendMode = BlendMode.DstOut) else null` at line 139 allocates a `ColorFilter` reference-type object and a `Color` on every recompose of every highlighted cell. When any `longclickedPhotos` mutation triggers recomposition of all cells (per finding #6), all highlighted cells rebuild their `ColorFilter`.
- **Fix:** Hoist to a top-level or `remember`-level constant: `val highlightFilter = remember { ColorFilter.tint(jetzyYellow.copy(alpha = 0.75f), BlendMode.DstOut) }` outside the grid, then reuse for all highlighted cells.

#### 🟡 LOW · O(n×m) identity scan in PickPhotosSubscreen exclude handler

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickPhotosSubscreen.kt:188` · _Algorithmic inefficiency_
- **Why it's slow:** Same nested identity-scan pattern as PickFilesSubscreen: `viewmodel.elementsToSend.removeAll { el -> toRemove.any { it === el } }` at line 188. Cold path (button click). Same bounded worst-case argument applies.
- **Fix:** Same fix: pre-build an identity-based `HashSet` from `toRemove` before calling `removeAll`.

#### 🟡 LOW · Text label built with string template `"${i+1}       ${text.text}"` — new String allocated every recompose

- **Where:** `shared/src/commonMain/kotlin/jetzy/ui/filepicking/PickTextSubscreen.kt:122` · _Allocation / GC churn in hot path_
- **Why it's slow:** `"${i+1}       ${text.text}"` at line 122 allocates a new `String` on every recomposition of the list item. The seven hardcoded spaces are also fragile layout alignment.
- **Fix:** Cache with `remember(i, text.text) { "${i+1}  ${text.text}" }`, or use a `Row` with a fixed-width number `Text` and a separate content `Text` with a `Spacer`, which is layout-correct and allocation-free.

### UI · Theme — 5 (1H / 1M / 3L)

#### 🔴 HIGH · AppTypography and FontFamily objects recreated on every recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/theme/Type.kt:11` · _Compose recomposition — allocation in composition_
- **Why it's slow:** `bodyFontFamily`, `displayFontFamily`, and `AppTypography` are plain `@Composable` getters with no `remember`. Each read of `bodyFontFamily` or `displayFontFamily` calls `FontFamily(Font(...))`, allocating a new object. `AppTypography` reads both font families and passes them to 15 `.copy()` calls on `TextStyle` objects. `JetzyTheme` (Theme.kt line 262) reads `AppTypography` directly in its composable body without `remember`, so every recomposition of `JetzyTheme` allocates 2 `FontFamily` and 15 `TextStyle` objects.
- **Fix:** Wrap `bodyFontFamily` and `displayFontFamily` in `remember` at their use site, or hoist them into a top-level `@Composable` function that wraps the entire `Typography(...)` block in `remember(bodyFontFamily, displayFontFamily)`. Since font resources are constant, the cleanest fix is to load them once via a module-level `@Composable` wrapper with `remember { FontFamily(...) }`.

#### 🟠 MED · getScaleFactor recomputes a constant base-diagonal on every screen-change

- **Where:** `shared/src/commonMain/kotlin/jetzy/theme/SspSdp.kt:30` · _Redundant recomputation in hot path_
- **Why it's slow:** `baseDiagonal = sqrt(320f.pow(2) + 480f.pow(2))` is inside the `remember(screenSize, density.density)` block, so it re-executes whenever screen size or density changes — but that is rare, not per-frame. The more substantive problem is that `.sdp` and `.ssp` are individual `@Composable` getters that each call `getScaleFactor()` separately, each independently reading `LocalDensity` and `LocalWindowInfo` from the composition local tree. This prevents Compose from scoping recompositions narrowly: every leaf composable that uses `.sdp` or `.ssp` reads both locals and will re-execute if either changes.
- **Fix:** Extract `baseDiagonal` as a top-level `private val BASE_DIAGONAL = sqrt(320f.pow(2) + 480f.pow(2))`. More impactfully, provide the scale factor through a single `CompositionLocal` set once at the theme root and consumed without re-querying locals at each leaf call site.

#### 🟡 LOW · getScaleFactor() reads two CompositionLocals per .sdp/.ssp usage inside itemsIndexed

- **Where:** `shared/src/commonMain/kotlin/jetzy/theme/SspSdp.kt:36` · _Compose recomposition / main thread work_
- **Why it's slow:** `getScaleFactor()` reads `LocalDensity.current` and `LocalWindowInfo.current` and does `remember(screenSize, density.density) { sqrt(...) }`. Inside a `LazyGrid`'s `itemsIndexed`, each cell has its own composition scope and its own `remember` slot, so each cell independently reads both CompositionLocals and performs the remember lookup. The sqrt computation itself is memoised per cell, but N cells × 8 sdp call sites each do 2 local reads and 1 remember lookup. CompositionLocal reads are a thread-local map lookup — fast but not free in a tight loop.
- **Fix:** Read `LocalDensity` and `LocalWindowInfo` once at the composable level (e.g. at the top of `FileFolderGridView`), compute the scale factor once via `remember`, and pass precomputed `Dp` values down into the grid rather than recomputing per cell.

#### 🟡 LOW · Four unused ColorScheme objects (lightScheme, darkScheme, highContrast*) kept alive in memory

- **Where:** `shared/src/commonMain/kotlin/jetzy/theme/Theme.kt:13` · _Wasted memory — unused allocations_
- **Why it's slow:** Six `ColorScheme` objects are allocated as module-level `private val`s. Only `mediumContrastLightColorScheme` (line 89) and `mediumContrastDarkColorScheme` (line 165) are referenced in `JetzyTheme` (line 261). `lightScheme` (line 13), `darkScheme` (line 51), `highContrastLightColorScheme` (line 127), and `highContrastDarkColorScheme` (line 203) are never referenced anywhere in the reviewed files. Each `ColorScheme` holds ~29 `Color` fields, so four unused objects represent a small but entirely avoidable heap allocation that persists for the process lifetime.
- **Fix:** Delete the four unused `ColorScheme` vals. If future high-contrast mode support is planned, convert them to `by lazy { ... }` so the allocation is deferred until actually needed.

#### 🟡 LOW · JetzyTheme reads nightMode and calls isDark() duplicating AdamScreen's read

- **Where:** `shared/src/commonMain/kotlin/jetzy/theme/Theme.kt:257` · _Redundant state read — duplicated dark-theme query_
- **Why it's slow:** `JetzyTheme` reads `LocalViewmodel.current` and calls `viewmodel.nightMode.collectAsState()` (Theme.kt lines 257-258), plus `nightMode.isDark()` which calls `isSystemInDarkTheme()` when the value is `NightMode.SYSTEM`. AdamScreen also reads `viewmodel.nightMode.collectAsState()` and `isSystemInDarkTheme()` (lines 103-104). This creates two independent `collectAsState` subscriptions on the same `MutableStateFlow`. When nightMode changes both scopes recompose, but this is bounded to user-initiated theme changes — not a continuous hot path. The `LocalViewmodel` read also means `JetzyTheme`'s scope is tied to viewmodel changes.
- **Fix:** Compute the dark-theme boolean once in `AdamScreen` and pass it to `JetzyTheme` as a parameter: `fun JetzyTheme(darkTheme: Boolean, content: @Composable () -> Unit)`. Remove the `collectAsState` and `LocalViewmodel` reads from inside `JetzyTheme`. This eliminates the second subscription and decouples the theme from viewmodel state.

### Lifecycle / entry — 3 (0H / 1M / 2L)

#### 🟠 MED · FLAG_KEEP_SCREEN_ON set unconditionally for entire app lifetime

- **Where:** `shared/src/androidMain/kotlin/jetzy/MainActivity.kt:57` · _Resource leak degrading battery / performance over time_
- **Why it's slow:** `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` is set in `onCreate` (line 57) and never cleared with `clearFlags`. This prevents the screen from dimming or sleeping for the entire app session, including when the user is idle on the main screen with no active transfer. The foreground service separately acquires a `PARTIAL_WAKE_LOCK` (CPU-only) for background transfers, so the screen-on flag is only needed in the foreground transfer screen, not the full session.
- **Fix:** Apply `FLAG_KEEP_SCREEN_ON` only while a transfer is in progress: add the flag in `startBackgroundService()` and clear it with `window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` in `stopBackgroundService()`. The PARTIAL_WAKE_LOCK in JetzyForegroundService keeps the CPU alive when backgrounded.

#### 🟡 LOW · window.minimumSize = Dimension(...) set inside the Window composable body on every recomposition

- **Where:** `desktopApp/src/main/kotlin/jetzy/desktop/Main.kt:28` · _Side-effect in composition_
- **Why it's slow:** `window.minimumSize = Dimension(380, 600)` (line 28) executes directly inside the `Window { }` content lambda, not inside a `SideEffect` or `LaunchedEffect`. Compose may reinvoke the lambda during recomposition, causing a new `java.awt.Dimension` allocation and an AWT property setter dispatch on the EDT on every recomposition. While the setter is idempotent, the repeated dispatch is avoidable.
- **Fix:** Move the assignment into a `SideEffect { window.minimumSize = Dimension(380, 600) }` block. `SideEffect` runs after every successful recomposition but is the semantically correct slot for side-effects that synchronise Compose state to non-Compose systems.

#### 🟡 LOW · buildNotification() and PendingIntent.getActivity() called on every onStartCommand

- **Where:** `shared/src/androidMain/kotlin/jetzy/services/JetzyForegroundService.kt:42` · _Redundant object allocation in service lifecycle_
- **Why it's slow:** `onStartCommand` (line 42) calls `buildNotification()` unconditionally, which allocates a new `Intent`, calls `PendingIntent.getActivity(...)` (a Binder IPC), and builds a `NotificationCompat.Builder` chain. `acquireWakeLock` (line 55) does guard against re-acquiring an already-held wake lock (line 65: `if (wakeLock?.isHeld == true) return`), so `getSystemService` is only called when the wakelock isn't held — but `buildNotification` has no such guard. With `START_STICKY`, OS restarts after process death re-invoke `onStartCommand` triggering redundant IPC. In practice this is a very infrequent path (service start events), not a continuous performance concern.
- **Fix:** Cache the static `Notification` object as a field and build it once in `onCreate`. Re-use it in `onStartCommand`. Only rebuild if notification content changes (not the case here). The PendingIntent with `FLAG_UPDATE_CURRENT` can also be constructed once and reused.

### Models / utils — 20 (1H / 7M / 12L)

#### 🔴 HIGH · NSDate() object allocated on every generateTimestampMillis() call — fired per 512 KB chunk in send/receive hot loops

- **Where:** `shared/src/iosMain/kotlin/jetzy/utils/PlatformUtils.ios.kt:31` · _Allocations / GC churn in hot path_
- **Why it's slow:** generateTimestampMillis() constructs a new NSDate Objective-C heap object on every call. It is called on every 512 KB chunk in both the send loop (P2PManager.kt line 425) and receive loop (line 620) to compute the speed window. The speed-window update is guarded by `if (elapsed >= 1000L)` so the StateFlow update only fires once per second, but the NSDate allocation itself occurs every chunk regardless. At Wi-Fi throughput (~50 MB/s with a 512 KB buffer) this is ~100 NSDate alloc/init/ARC-release round-trips per second from the hot path alone.
- **Fix:** Replace with a direct POSIX call that avoids allocation: use clock_gettime(CLOCK_REALTIME) via kotlinx.cinterop, or mach_absolute_time() converted to milliseconds. Both are single syscalls with no heap allocation and no ObjC ARC overhead.

#### 🟠 MED · flattenFolder runs blocking DocumentFile.listFiles() SAF IPC calls without IO dispatcher context

- **Where:** `shared/src/androidMain/kotlin/jetzy/models/FolderFlattener.android.kt:18` · _Blocking IO on wrong dispatcher / missing withContext_
- **Why it's slow:** The Android `flattenFolder` implementation calls `DocumentFile.listFiles()` inside a recursive `traverse()` function without a `withContext` guard. Each `listFiles()` is a SAF Binder IPC call to the ContentProvider. The function is currently only called from `prepareElements()` which runs inside `p2pScope.launch { }` (scope uses `PreferablyIO = Dispatchers.IO`), so the blocking calls do land on an IO thread in practice. However, the `suspend` function lacks a `withContext` defensive wrapper that the equivalent desktop implementation (`FolderFlattener.desktop.kt` line 9) correctly includes, creating an asymmetry that will cause a main-thread block if the function is ever called from a UI coroutine.
- **Fix:** Wrap the function body in `withContext(PreferablyIO) { ... }` to match the desktop implementation and make the threading contract self-contained regardless of the caller's dispatcher.

#### 🟠 MED · JetzyElement.Text.source allocates a new Buffer + encodes the string on every call

- **Where:** `shared/src/commonMain/kotlin/jetzy/models/JetzyElement.kt:39` · _Allocations / GC churn_
- **Why it's slow:** The source property of Text creates a new Buffer and calls writeString() every time the property is read (line 39). Combined with size() calling encodeToByteArray() (line 40), the same text is encoded twice per transfer — once for the manifest size, once for streaming. A lazy-encoded byte array would satisfy both.
- **Fix:** Add `private val encoded by lazy { text.encodeToByteArray() }` and have source return Buffer().also { it.write(encoded) } and size() return encoded.size.toLong().

#### 🟠 MED · JetzyElement.Text.size() allocates a full ByteArray to measure UTF-8 size

- **Where:** `shared/src/commonMain/kotlin/jetzy/models/JetzyElement.kt:40` · _Allocations / GC churn_
- **Why it's slow:** Text.size() calls text.encodeToByteArray().size.toLong() — it allocates a complete byte[] copy of the text just to measure its encoded length. Combined with source also encoding the string (line 39), the same text is encoded twice per manifest entry. Called once per element when building the manifest.
- **Fix:** Eagerly encode the text once via lazy: `private val encoded by lazy { text.encodeToByteArray() }`. Have size() return encoded.size.toLong() and source read from the same byte array, eliminating the double encoding.

#### 🟠 MED · FontFamily(Font()) allocated twice without remember inside JetzyText

- **Where:** `shared/src/commonMain/kotlin/jetzy/utils/ComposeUtils.kt:48` · _Compose recomposition / GC churn_
- **Why it's slow:** `JetzyText` at line 48 creates `val font = FontFamily(Font(font))` without `remember`. The extension property `FontResource.font` at line 37 also creates `FontFamily(Font(this))` without `remember`. Every time `JetzyText` recomposes, one `FontFamily` object is allocated and discarded. The `TextStyle.copy()` allocation claim is less compelling: `TextStyle` is a data class and `.copy()` does allocate, but this is standard Compose practice and not specific to this call site. The font allocation is the real issue. Note: this file is not in the stated scope, so it is flagged but the verification confirms the code exists and matches.
- **Fix:** Cache the font: `val resolvedFont = remember(font) { FontFamily(Font(font)) }` inside `JetzyText`. For the extension property, callers should wrap the result in `remember`.

#### 🟠 MED · FontFamily(Font(...)) allocated fresh on every recomposition in JetzyText and OperationButton

- **Where:** `shared/src/commonMain/kotlin/jetzy/utils/ComposeUtils.kt:48` · _Compose recomposition — allocating in composition without remember_
- **Why it's slow:** JetzyText creates `val font = FontFamily(Font(font))` on line 48 with no `remember {}` wrapper — FontFamily and Font construction runs on every recomposition. The FontResource.font extension property (line 37) also calls FontFamily(Font(this)) without remember. OperationButton.kt (line 79) and VerticalCardButton.kt (line 83) have the same bare FontFamily(Font(...)) pattern.
- **Fix:** Wrap with remember: `val font = remember(font) { FontFamily(Font(font)) }` in JetzyText. Change the extension property getter to `@Composable get() = remember(this) { FontFamily(Font(this)) }`. Apply the same fix in OperationButton and VerticalCardButton.

#### 🟠 MED · Desktop getDeviceName() performs a blocking DNS lookup (InetAddress.getLocalHost()) that may block IO thread

- **Where:** `shared/src/desktopMain/kotlin/jetzy/utils/PlatformUtils.desktop.kt:20` · _Blocking IO on wrong thread_
- **Why it's slow:** InetAddress.getLocalHost() performs a reverse-DNS lookup that can block for the full system DNS timeout (typically 5–30 seconds) if the hostname is not in /etc/hosts or DNS is unreachable. getDeviceName() has no caching and is called 5 times per handshake from P2PManager (lines 514, 522, 532, 701, 711). The first call may occur during session setup on PreferablyIO, but subsequent calls in receiveFiles() can also run before the channel is established.
- **Fix:** Cache the result in a top-level `val cachedDeviceName: String by lazy { getDeviceName() }` initialized once on PreferablyIO during manager startup. All five handshake call sites reference the cached value.

#### 🟠 MED · generateTimestampMillis() allocates NSDate on every chunk in the hot transfer loop (iOS)

- **Where:** `shared/src/iosMain/kotlin/jetzy/utils/PlatformUtils.ios.kt:31` · _Allocations / GC churn in hot path_
- **Why it's slow:** generateTimestampMillis() calls NSDate() on every invocation. It is called inside the inner transfer loops of sendFiles (line 425) and receiveFiles (line 620) in P2PManager.kt, once per 512 KB chunk, to drive the per-second speed window. At 250 Mbps (~60 chunks/sec) this is 60 NSDate allocations per second plus associated ARC retain/release traffic across the Kotlin–ObjC interop boundary. NSDate itself is cheap, but the KN interop ARC overhead is non-trivial at this rate. The fix would eliminate all allocation for this timestamp.
- **Fix:** Replace NSDate().timeIntervalSince1970 with clock_gettime(CLOCK_REALTIME_COARSE) via the posix import (returns nanoseconds, divide by 1_000_000 for millis), or use NSProcessInfo.processInfo.systemUptime * 1000 which is a simple floating-point register read with no allocation. Cache-via-lazy-val is not applicable since the value changes every call.

#### 🟡 LOW · String concatenation builds new path string per file during folder traversal

- **Where:** `shared/src/androidMain/kotlin/jetzy/models/FolderFlattener.android.kt:20` · _Allocation / GC churn in repeated path_
- **Why it's slow:** The `traverse` function allocates a new `String` via `"$currentPath/$childName"` for each directory level and each file entry. For a folder with D directory levels and F files, this creates O(D × F) intermediate strings. However, the entire traversal is dominated by `DocumentFile.listFiles()` Binder IPC latency (each call crosses a process boundary to the ContentProvider), which is orders of magnitude more expensive than string allocation. The practical allocation overhead is negligible compared to the IPC cost.
- **Fix:** No action needed for performance. If code style dictates, a `StringBuilder`-based path assembly reduces intermediate allocations, but benchmarks would show no measurable improvement given the IPC dominance.

#### 🟡 LOW · isWifiAwareSupported() calls PackageManager.hasSystemFeature on every invocation — no caching

- **Where:** `shared/src/androidMain/kotlin/jetzy/utils/PlatformUtils.android.kt:31` · _Repeated expensive system lookup that should be cached_
- **Why it's slow:** `isWifiAwareSupported()` calls `PackageManager.hasSystemFeature(FEATURE_WIFI_AWARE)` which is a Binder IPC call every time. Wi-Fi Aware hardware capability is static for the device lifetime. The function is called from `P2pTechnology.localCapabilitiesMask()` (invoked during handshake at transfer start, ~2–3 times per session) and from MainActivity factory lambdas (invoked on demand when the user selects a transport). Total call count per session is small (~3–5). The overhead is real but bounded and infrequent — not a hot-path issue.
- **Fix:** Cache the result with `private val wifiAwareSupported: Boolean by lazy { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) }` initialized at application start. This is a correctness improvement (removes a Binder dependency from the handshake path) as much as a performance one.

#### 🟡 LOW · isWifiAwareSupported() calls hasSystemFeature on every invocation without caching

- **Where:** `shared/src/androidMain/kotlin/jetzy/utils/PlatformUtils.android.kt:31` · _Repeated expensive lookup — uncached feature check_
- **Why it's slow:** `isWifiAwareSupported()` calls `ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)` on every invocation without caching. It is called in `getSuitableP2pManager` and in lambdas inside `getFallbackP2pManagers` (MainActivity lines 97, 131, 144). These are all user-action-triggered code paths (manager selection on session start, or the user tapping 'Try a different transport'), not per-frame or per-poll paths. The call overhead is real but bounded to a handful of times per session, making this a low-priority issue.
- **Fix:** Cache the result: `private val wifiAwareSupported: Boolean by lazy { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) false else runCatching { MainActivity.contextGetter().packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) }.getOrDefault(false) }`. Then `isWifiAwareSupported()` returns the cached value.

#### 🟡 LOW · JetzyElement.Text.size() re-encodes the entire text to bytes just to get its length

- **Where:** `shared/src/commonMain/kotlin/jetzy/models/JetzyElement.kt:40` · _Allocation / GC_
- **Why it's slow:** Text.size() at line 40 calls `text.encodeToByteArray().size.toLong()`, allocating a full UTF-8 byte array only to immediately discard it after reading .size. Separately, the `source` getter at line 39 allocates a new Buffer and encodes the string again. So the string is encoded twice during the manifest/send phase — once for size() and once for source. This is a one-time cold-path cost, not a loop, so the practical impact is small.
- **Fix:** Compute the byte length without allocation using `kotlinx.io.Buffer().apply { writeString(text) }.size` or by iterating the string with a UTF-8 byte-count helper. Storing the encoded bytes at construction time would also unify size() and source() into a single encode.

#### 🟡 LOW · Naive split(":") in QRData.toQRData() allocates an unbounded List

- **Where:** `shared/src/commonMain/kotlin/jetzy/models/QRData.kt:51` · _Allocations / GC churn in hot path_
- **Why it's slow:** `split(":")` at line 51 uses the no-limit overload. In normal operation the QR string has at most 7 colon-separated fields (all colons in field values are percent-escaped to `%3A`), so the list always has 5–7 elements. The no-limit form doesn't cause extra splits in practice, but using `limit = 7` is a cheap correctness guard against malformed QR strings with unexpected trailing content.
- **Fix:** Use `split(":", limit = 7)` to cap the list at 7 elements. This is a one-shot parse (called once when a QR is scanned/submitted), so the performance impact is negligible either way, but `limit = 7` makes the intent explicit.

#### 🟡 LOW · qrEscape() always allocates a StringBuilder even when the input contains no special characters

- **Where:** `shared/src/commonMain/kotlin/jetzy/models/QRData.kt:72` · _Allocations / GC churn in hot path_
- **Why it's slow:** `qrEscape()` at lines 72–80 always enters the `buildString` path regardless of input content, allocating a `StringBuilder` and copying every character even when the input has no `%` or `:`. `qrUnescape()` at line 88 already uses an early-exit guard (`if ('%' !in this) return this`). This function is called on every QR encode (5 times per `QRData.toString()`), which happens at least once per screen show on Android and on every composition of an animated `QrCodeBlock`.
- **Fix:** Add an early-exit guard at the top of `qrEscape()`: `if ('%' !in this && ':' !in this) return this`. For typical SSIDs and device names that contain no special characters, this returns the original string reference with no allocation.

#### 🟡 LOW · qrUnescape() creates a substring on every %-escape via s.substring(i+1, i+3)

- **Where:** `shared/src/commonMain/kotlin/jetzy/models/QRData.kt:95` · _Allocations / GC churn_
- **Why it's slow:** For each percent-encoded character, qrUnescape() calls s.substring(i+1, i+3) to extract two hex digits, then calls toIntOrNull(16). The substring() allocates a new String object per escape sequence. For typical QR payloads with only a few encoded characters (% and : in SSID/password/device name) the impact is negligible. The optimization of reading two chars inline is valid.
- **Fix:** Decode the two hex chars inline without substring: val hi = s[i+1].digitToIntOrNull(16); val lo = s[i+2].digitToIntOrNull(16); if (hi != null && lo != null) append((hi shl 4 or lo).toChar())

#### 🟡 LOW · InitializeCoilSupportForFileKit runs setSingletonImageLoaderFactory inside composition body — re-runs on every AdamScreen recomposition

- **Where:** `shared/src/commonMain/kotlin/jetzy/utils/CommonUtils.kt:25` · _Compose recomposition — heavy work inside composition_
- **Why it's slow:** InitializeCoilSupportForFileKit() is a @Composable function that calls setSingletonImageLoaderFactory on every call. It is invoked from AdamScreen.kt line 89 directly in the composition body without a LaunchedEffect or remember guard. Every recomposition of AdamScreen re-registers a new factory, which involves locking and object allocation in Coil's singleton.
- **Fix:** Wrap the call site with LaunchedEffect(Unit) { InitializeCoilSupportForFileKit() } in AdamScreen, or move the call to a non-composable one-time init path (Application.onCreate on Android, main() on desktop/iOS) so it runs exactly once per app launch.

#### 🟡 LOW · Desktop getPersistentStoragePath() calls mkdirs() on every invocation

- **Where:** `shared/src/desktopMain/kotlin/jetzy/utils/PlatformUtils.desktop.kt:63` · _Redundant re-initialization / repeated expensive lookup_
- **Why it's slow:** getPersistentStoragePath() rebuilds the File path from system properties and calls dir.mkdirs() on every call (line 63). getAvailableStorageBytes() (line 35) calls getPersistentStoragePath() itself, so both functions execute filesystem stat + conditional mkdirs repeatedly. The path is constant for the JVM's lifetime.
- **Fix:** Cache the computed path in a top-level lazy val so system property reads, path construction, and the mkdirs check happen only once per JVM lifetime.

#### 🟡 LOW · iOS getPersistentStoragePath() calls NSSearchPathForDirectoriesInDomains on every invocation

- **Where:** `shared/src/iosMain/kotlin/jetzy/utils/PlatformUtils.ios.kt:43` · _Redundant re-initialization / repeated expensive lookup_
- **Why it's slow:** NSSearchPathForDirectoriesInDomains is a Foundation call that returns an autoreleased NSArray every time. It is called inside getPersistentStoragePath() (line 43-45) which is in turn called inside getAvailableStorageBytes(). The path never changes within an app lifecycle. The macOS implementation (PlatformUtils.macos.kt line 44) has the identical pattern.
- **Fix:** Cache with a top-level `val persistentStoragePath: String by lazy { ... }` in both iOS and macOS source sets.

#### 🟡 LOW · getDeviceName() performs a uname() syscall and string table scan on every invocation (iOS)

- **Where:** `shared/src/iosMain/kotlin/jetzy/utils/PlatformUtils.ios.kt:62` · _Redundant re-initialization / repeated expensive lookups_
- **Why it's slow:** getDeviceName() calls uname() and walks a when() chain on each invocation. On iOS, getDeviceName() is called roughly 7 times per session: once during MpcP2PM initialization for localPeerID, once during startDiscoveryAndAdvertising, twice during the handshake (writeHello), and three times when building ManifestAckFrame variants. All of these are cold/handshake paths, not the per-chunk data transfer loop. A uname() syscall costs roughly 1–5 microseconds; seven calls per session adds ~35 microseconds total — unmeasurable against session-level overhead. The lazy caching suggestion is correct good practice but addresses no meaningful performance issue.
- **Fix:** Add private val cachedDeviceName: String by lazy { /* current body */ } and call cachedDeviceName from the actual function. This is a clean-code improvement, not a performance fix.

#### 🟡 LOW · NSHost.currentHost() called twice in macOS getDeviceName() — two Objective-C bridge calls per invocation

- **Where:** `shared/src/macosMain/kotlin/jetzy/utils/PlatformUtils.macos.kt:29` · _Redundant re-initialization / repeated expensive lookup_
- **Why it's slow:** getDeviceName() calls NSHost.currentHost() twice in the same expression (lines 29-30) — once for localizedName and once for name — each crossing the Kotlin-Native to ObjC bridge. The host object should be retrieved once and both properties read from the single reference.
- **Fix:** val host = NSHost.currentHost(); return host.localizedName?.takeIf { it.isNotBlank() } ?: host.name?.takeIf { it.isNotBlank() } ?: NSUserName()... Also add a top-level lazy val to avoid repeated calls per handshake.

### Permissions — 2 (0H / 1M / 1L)

#### 🟠 MED · Reflection lookup (getDeclaredMethod) on every isGrantedNow poll for hotspot state

- **Where:** `shared/src/androidMain/kotlin/jetzy/permissions/AndroidPermissionRequirements.kt:220` · _Repeated expensive lookup — reflection in polling path_
- **Why it's slow:** `isWifiApEnabled` (line 220) calls `wifi.javaClass.getDeclaredMethod("isWifiApEnabled")` and then `method.isAccessible = true` followed by `method.invoke(wifi)` on every invocation. The `isGrantedNow` lambda for the `mobileHotspotOff` requirement (line 143) calls this function directly, and the dialog's `produceState` block invokes `isGrantedNow()` every 400ms. The `Method` object is re-looked up on every call — reflection method lookup defeats JIT inlining and is measurably more expensive than a direct call.
- **Fix:** Cache the `Method` object as a field on `AndroidPermissionRequirements` or look it up once lazily: `private val wifiApEnabledMethod by lazy { runCatching { WifiManager::class.java.getDeclaredMethod("isWifiApEnabled").also { it.isAccessible = true } }.getOrNull() }`. The polling lambda then does only `method?.invoke(wifi) as? Boolean ?: false`.

#### 🟡 LOW · permissionRequirements get() re-fetches system services on every recomposition

- **Where:** `shared/src/androidMain/kotlin/jetzy/permissions/AndroidPermissionRequirements.kt:80` · _Repeated expensive lookup — system service in recomposition path_
- **Why it's slow:** Because `permissionRequirements` is a `get()` property rebuilt each recomposition, the builder functions `wifiEnabled()` (line 80), `mobileHotspotOff()` (line 137), and `ignoreBatteryOptimizations()` (line 166) each call `activity.applicationContext.getSystemService(...)` to capture a service reference in their closures. The `isGrantedNow` lambdas themselves don't re-fetch; only the builder calls do. `getSystemService` is internally cached on Android, but allocating new wrapper references and new `PermissionRequirement` instances per-recomposition adds GC pressure. The root cause is the uncached `permissionRequirements` getter (finding 3), not `getSystemService` itself.
- **Fix:** Fix is the same as finding 3: cache `permissionRequirements` as a `lazy val` on the manager instance. This eliminates all redundant `getSystemService` calls and object allocations at once.
