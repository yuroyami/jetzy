package jetzy.p2p

enum class P2pOperation { SEND, RECEIVE }

/** Which side stands up the data-plane server for a chosen transport; the other dials it. */
enum class Role { HOST, CLIENT }

/**
 * For a given peer pair, which device can/should host a transport's data plane.
 * [LOCAL]/[REMOTE] are forced by platform asymmetry (e.g. only Android can host a hotspot);
 * [EITHER] is symmetric and resolved by a deterministic tiebreak; [NEITHER] excludes the
 * transport for this pair.
 */
enum class HostAffinity { LOCAL, REMOTE, EITHER, NEITHER }