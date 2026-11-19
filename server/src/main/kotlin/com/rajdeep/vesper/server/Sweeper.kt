package com.rajdeep.vesper.server

import com.mongodb.client.model.Filters
import com.rajdeep.vesper.shared.HallDeleted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Hard-deletes expired content. Blobs are always deleted before the documents that reference them.
 * Runs every 30 s; the first pass after boot catches up on anything that went overdue while asleep.
 */
class Sweeper(private val db: Db, private val media: MediaService, private val hub: Hub) {
    private val log = LoggerFactory.getLogger("Sweeper")
    @Volatile var lastSweep: Long = 0
    @Volatile var lastReconcile: Long = 0

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { sweep() }.onFailure { log.warn("sweep failed: {}", it.message) }
                if (ServerClock.now() - lastReconcile > 60 * 60 * 1000) runCatching { reconcile() }.onFailure { log.warn("reconcile failed: {}", it.message) }
                delay(30_000)
            }
        }
    }

    suspend fun sweep() {
        val now = ServerClock.now()
        val nowD = now.asDate()
        val stale = (now - 60 * 60 * 1000).asDate()
        // 1. Blobs that are due: attached+expired, or uploaded but never attached within 1 h.
        val dueMedia = db.media.find(
            Filters.or(
                Filters.and(Filters.eq("st", "attached"), Filters.lte("exp", nowD)),
                Filters.and(Filters.`in`("st", listOf("pending", "committed")), Filters.lte("at", stale)),
            ),
        ).limit(1000).toList().map { it.str("_id")!! }
        if (dueMedia.isNotEmpty()) media.purge(dueMedia)

        // 2. Hall messages. A media message is removed only once its blob is confirmed gone.
        val expired = db.hall.find(Filters.lte("exp", nowD)).limit(2000).toList()
        if (expired.isNotEmpty()) {
            val mids = expired.mapNotNull { it.str("mid") }
            val stillStored = if (mids.isEmpty()) emptySet() else db.media.find(Filters.`in`("_id", mids)).toList().map { it.str("_id") }.toSet()
            val ids = expired.filter { it.str("mid") == null || it.str("mid") !in stillStored }.map { it.str("_id")!! }
            if (ids.isNotEmpty()) {
                db.hall.deleteMany(Filters.`in`("_id", ids))
                hub.toAll(HallDeleted(ids))
            }
        }

        // 3. Everything else with an expiry (TTL indexes are only a backstop).
        db.envelopes.deleteMany(Filters.lte("exp", nowD))
        db.privateMeta.deleteMany(Filters.lte("exp", nowD))
        db.activity.deleteMany(Filters.lte("exp", nowD))
        db.refresh.deleteMany(Filters.lte("exp", nowD))
        lastSweep = now
    }

    /** Deletes stored blobs older than 25 h (longest legal lifetime is 24 h + 1 h upload window). */
    suspend fun reconcile() {
        val now = ServerClock.now()
        val orphans = media.store.listOlderThan(now - 25 * 60 * 60 * 1000)
        if (orphans.isNotEmpty()) {
            val gone = media.store.delete(orphans)
            if (gone.isNotEmpty()) db.media.deleteMany(Filters.`in`("key", gone))
            log.info("reconciler removed {} orphan blobs", gone.size)
        }
        lastReconcile = now
    }
}
