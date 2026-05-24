package com.nuvio.tv.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coalesces concurrent `RivenAvailabilityService.fetch` calls into a single
 * HTTP roundtrip.
 *
 * Home boot fires multiple `CatalogRepositoryImpl.filterToAvailable` calls
 * in parallel — one per built-in row. Without this, each row triggers its
 * own `POST /availability`. With the user's six-row home, that was up to
 * six near-simultaneous round trips for the same `/availability` endpoint.
 *
 * Strategy: the first caller becomes the leader and waits a short
 * coalesce window for other callers to enqueue their IDs, then issues one
 * combined request. Followers attach to the same `CompletableDeferred`
 * and read out just the subset of IDs they cared about.
 *
 * The window is small (a few tens of ms) so single calls don't see
 * meaningful added latency — but typical home boot flushes 4-6 rows in
 * one trip.
 */
@Singleton
class AvailabilityBatcher @Inject constructor(
    private val service: RivenAvailabilityService,
) {
    companion object {
        // Long enough to gather a tab-burst of row fetches (they arrive
        // within ~10-20ms of each other on home boot) without adding
        // perceptible latency to one-off callers.
        private const val COALESCE_WINDOW_MS = 60L
    }

    private val mutex = Mutex()
    private var pending: Pending? = null

    private class Pending {
        // Merged input across all coalesced callers, keyed by Stremio
        // addon base URL — preserving the per-addon dispatch the underlying
        // service uses.
        val idsByAddon = mutableMapOf<String, MutableSet<String>>()
        val deferred = CompletableDeferred<Map<String, Boolean>>()
    }

    suspend fun fetch(idsByAddon: Map<String, List<String>>): Map<String, Boolean> {
        if (idsByAddon.isEmpty()) return emptyMap()

        val joined: CompletableDeferred<Map<String, Boolean>>
        val isLeader: Boolean
        mutex.withLock {
            val existing = pending
            if (existing == null) {
                pending = Pending()
                isLeader = true
            } else {
                isLeader = false
            }
            val target = pending!!
            idsByAddon.forEach { (url, ids) ->
                target.idsByAddon.getOrPut(url) { mutableSetOf() }.addAll(ids)
            }
            joined = target.deferred
        }

        if (isLeader) {
            // Wait the coalesce window so concurrent callers can attach to
            // this batch, then snapshot + clear so the next caller starts
            // a fresh batch.
            //
            // CRITICAL: every exit path must clear `pending` AND complete
            // the deferred. Without the finally, a cancelled leader (caller
            // scope torn down mid-`delay`) leaves `pending` non-null and
            // its deferred un-completed forever — every subsequent caller
            // joins a stale batch that never resolves and hangs.
            var snapshot: Pending? = null
            try {
                delay(COALESCE_WINDOW_MS)
                mutex.withLock {
                    snapshot = pending
                    pending = null
                }
                val flat = snapshot!!.idsByAddon.mapValues { it.value.toList() }
                snapshot!!.deferred.complete(service.fetch(flat))
            } catch (t: Throwable) {
                // Cancellation OR fetch failure: clear pending if we haven't
                // already, and propagate the failure to followers so their
                // `runCatching` sees the exception instead of hanging.
                if (snapshot == null) {
                    mutex.withLock {
                        snapshot = pending
                        pending = null
                    }
                }
                snapshot?.deferred?.completeExceptionally(t)
                throw t
            }
        }

        val full = joined.await()
        // Return only the keys this caller asked about — followers shouldn't
        // be confused by entries from sibling rows that joined the batch.
        val myIds = idsByAddon.values.flatMapTo(mutableSetOf()) { it }
        return if (myIds.size == full.size) full else full.filterKeys { it in myIds }
    }
}
