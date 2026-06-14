package jetzy.p2p

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The transport that won the race plus the live link it produced. */
data class RaceWinner<T : Any>(val match: TransportMatch, val link: T)

/**
 * The **executor** the negotiator brain was missing: it runs [TransportCoordinator.schedule]'s
 * Happy-Eyeballs plan for real. Given the staggered [ConnectAttempt]s and a way to *try* a single
 * transport, it launches the attempts on their offsets, keeps whichever establishes a link **first**,
 * and tears down every loser — so connecting just succeeds, fast, with no manual "try a different
 * transport" ladder and no dead-end timeout.
 *
 * It is deliberately generic over the link type `T` (a Ktor `Connection`, a direct channel pair, an
 * MPC session — the racer doesn't care) and takes the connect/close behaviour as lambdas, so it
 * couples to nothing and is unit-testable with a fake link. The integration half — having each real
 * [jetzy.managers.P2PManager] expose a "connect that returns a link **without** auto-starting the
 * transfer" so a loser can be cancelled before any bytes move — is the per-platform, device-validated
 * step that consumes this.
 */
object TransportRacer {

    /**
     * Race [attempts] (already staggered by [TransportCoordinator.schedule]) and return the first to
     * connect, or null if every attempt fails / the overall [overallTimeoutMs] elapses.
     *
     * @param connect tries one transport and returns a live link, or null on failure. Must be
     *   cancellation-cooperative: when a faster attempt wins, in-flight losers are cancelled and any
     *   half-open link they were standing up should be released by [connect] honouring cancellation.
     * @param close tears down a link that connected but lost the race (arrived after the winner).
     *   Called under [NonCancellable] so a late winner can never strand a loser's open socket.
     */
    suspend fun <T : Any> race(
        attempts: List<ConnectAttempt>,
        overallTimeoutMs: Long = 20_000,
        connect: suspend (TransportMatch) -> T?,
        close: suspend (T) -> Unit,
    ): RaceWinner<T>? {
        if (attempts.isEmpty()) return null

        return withTimeoutOrNull(overallTimeoutMs) {
            coroutineScope {
                val winner = CompletableDeferred<RaceWinner<T>?>()
                val gate = Mutex()
                var claimed = false
                var pending = attempts.size

                val jobs = attempts.map { attempt ->
                    launch {
                        delay(attempt.startDelayMs)
                        val link: T? = try {
                            connect(attempt.match)
                        } catch (e: CancellationException) {
                            throw e // a winner cancelled us mid-connect — propagate, drop this attempt
                        } catch (_: Throwable) {
                            null // a real connect failure is just a lost rung, not a crash
                        }

                        // Claim + disposition under NonCancellable so a concurrent winner can't
                        // cancel us between "got a link" and "decided what to do with it" → no leak.
                        withContext(NonCancellable) {
                            var won = false
                            gate.withLock {
                                when {
                                    link == null -> {
                                        if (--pending == 0 && !claimed) winner.complete(null)
                                    }
                                    !claimed -> {
                                        claimed = true
                                        won = true
                                        winner.complete(RaceWinner(attempt.match, link))
                                    }
                                    // else: someone already won — this link lost, fall through to close
                                }
                            }
                            if (link != null && !won) runCatching { close(link) }
                        }
                    }
                }

                val result = winner.await()
                jobs.forEach { it.cancel() } // stop losers still mid-connect
                result
            }
        }
    }
}
