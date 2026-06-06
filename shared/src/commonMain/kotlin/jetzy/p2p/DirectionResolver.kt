package jetzy.p2p

import jetzy.utils.Platform

/** Which way the bytes flow this session. [NONE] = neither side staged files → don't even try. */
enum class TransferDirection { SEND, RECEIVE, NONE }

/**
 * One peer's transfer intent, as advertised in the HELLO frame: *do I have files staged to send*,
 * plus the stable identity needed to break a both-offering tie deterministically.
 */
data class TransferParty(
    val offeringFiles: Boolean,
    val platform: Platform,
    val deviceName: String,
    /** Random per-session nonce from the HELLO; breaks the both-offering tie for same-name devices. */
    val tiebreaker: Int = 0,
)

/**
 * Decides who sends and who receives — *derived from intent, never declared as a user-picked mode.*
 *
 * The protocol is manifest-driven: whoever has files writes the manifest. So "sender" is simply
 * "the side that staged files." This resolver turns the two HELLO intents into that decision, and
 * — crucially — it is **symmetric**: both peers run it on the same two parties and reach the same
 * answer with zero coordination round-trips (mirroring [TransportNegotiator]'s host tiebreak). That
 * symmetry is what lets the app delete the up-front "Send / Receive" choice entirely.
 *
 * Cases:
 *  - exactly one side offering → that side sends (the 95% case, no ambiguity);
 *  - neither offering → [TransferDirection.NONE]; the caller shows "add files to share" instead of
 *    the legacy bug where two receivers connect and hang forever waiting for a manifest;
 *  - both offering → the antisymmetric [key] picks one as sender this round (the other can swap
 *    after, once bidirectional lands). Both peers compute the identical winner.
 */
object DirectionResolver {

    fun resolve(local: TransferParty, remote: TransferParty): TransferDirection = when {
        local.offeringFiles && !remote.offeringFiles -> TransferDirection.SEND
        !local.offeringFiles && remote.offeringFiles -> TransferDirection.RECEIVE
        !local.offeringFiles && !remote.offeringFiles -> TransferDirection.NONE
        // Both have files: deterministic, antisymmetric pick — local sends iff its key sorts
        // at-or-after the remote's. Exactly one side sees `>=` true, so the two peers agree.
        else -> if (key(local) >= key(remote)) TransferDirection.SEND else TransferDirection.RECEIVE
    }

    // Nonce first so it dominates: two same-name, same-platform devices still get distinct keys
    // (collision ≈ 2^-32, at which point the residual both-SEND mirrors the negotiator's accepted tie).
    private fun key(p: TransferParty): String = "${p.tiebreaker} ${p.platform.ordinal} ${p.deviceName}"
}
