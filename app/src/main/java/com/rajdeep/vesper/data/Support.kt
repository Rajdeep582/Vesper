package com.rajdeep.vesper.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rajdeep.vesper.MainActivity
import com.rajdeep.vesper.R
import com.rajdeep.vesper.core.db.VesperDatabase
import com.rajdeep.vesper.core.net.Realtime
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.shared.MediaRef
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.VesperJson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Local notifications for private messages and Hall broadcasts. Previews are built on-device; the push itself never carries content. */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: Settings,
) {
    @Volatile var viewingPeer: String? = null
    @Volatile var viewingHall: Boolean = false
    @Volatile var foreground: Boolean = false
    private val byMessage = ConcurrentHashMap<String, Pair<Int, Long>>()

    init {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DIRECT, ctx.getString(R.string.channel_direct), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_direct_desc)
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HALL, "Hall", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Public Hall messages"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            },
        )
    }

    fun isViewing(peer: String) = foreground && viewingPeer == peer
    fun isViewingHall() = foreground && viewingHall

    fun direct(peer: String, name: String, kind: MessageKind, text: String?, mid: String, expiresAt: Long) {
        if (isViewing(peer)) return
        if (settings.isTurnOffInboxNotifications() || settings.isPeerMuted(peer)) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val preview = when (kind) { MessageKind.TEXT -> text?.take(120) ?: ""; MessageKind.VOICE -> "Voice message"; MessageKind.IMAGE -> "Photo" }
        val intent = PendingIntent.getActivity(
            ctx, peer.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PEER, peer).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val id = peer.hashCode()
        val n = NotificationCompat.Builder(ctx, CHANNEL_DIRECT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8FA3C8.toInt())
            .setContentTitle(name)
            .setContentText(preview)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setGroup("direct")
            .setTimeoutAfter((expiresAt - System.currentTimeMillis()).coerceAtLeast(1000))
            .setContentIntent(intent)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
        byMessage[mid] = id to expiresAt
    }

    fun hall(name: String, kind: MessageKind, text: String?, id: String, expiresAt: Long) {
        if (isViewingHall()) return
        if (settings.isTurnOffHallNotifications()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val preview = when (kind) { MessageKind.TEXT -> text?.take(120) ?: ""; MessageKind.VOICE -> "Voice message"; MessageKind.IMAGE -> "Photo" }
        val intent = PendingIntent.getActivity(
            ctx, 7002,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notifId = 7002
        val n = NotificationCompat.Builder(ctx, CHANNEL_HALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8FA3C8.toInt())
            .setContentTitle("Hall • $name")
            .setContentText(preview)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setTimeoutAfter((expiresAt - System.currentTimeMillis()).coerceAtLeast(1000))
            .setContentIntent(intent)
            .build()
        NotificationManagerCompat.from(ctx).notify(notifId, n)
        byMessage[id] = notifId to expiresAt
    }

    fun cancelFor(peer: String) = NotificationManagerCompat.from(ctx).cancel(peer.hashCode())
    fun cancelHall() {
        NotificationManagerCompat.from(ctx).cancel(7002)
        byMessage.entries.removeIf { it.value.first == 7002 }
    }

    fun cancelDirectAll() {
        // Also clears notifications posted by an earlier process (byMessage only knows this one's).
        val sys = ctx.getSystemService(NotificationManager::class.java)
        runCatching { sys.activeNotifications.filter { it.notification.channelId == CHANNEL_DIRECT }.forEach { sys.cancel(it.id) } }
        byMessage.entries.removeIf { it.value.first != 7002 }
    }

    fun cancelExpired(mids: Collection<String>) {
        mids.forEach { mid -> byMessage.remove(mid)?.let { NotificationManagerCompat.from(ctx).cancel(it.first) } }
    }

    fun cancelAll() = NotificationManagerCompat.from(ctx).cancelAll()

    companion object {
        const val CHANNEL_DIRECT = "vesper_direct"
        const val CHANNEL_HALL = "vesper_hall"
    }
}

/**
 * Hard-deletes expired messages locally: rows, cached ciphertext, staged blobs, decrypted memory copies and their
 * notifications. Runs on a ticker while the app is open and from [SyncWorker] in the background.
 */
@Singleton
class Expiry @Inject constructor(
    private val db: VesperDatabase,
    private val media: MediaRepository,
    private val notifier: Notifier,
    private val session: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun startTicker() {
        if (job?.isActive == true) return
        job = scope.launch {
            sweep(full = true)
            while (isActive) {
                val next = db.messages().nextExpiry()
                val wait = if (next == null) 60_000L else (next - session.now()).coerceIn(500L, 60_000L)
                delay(wait)
                sweep()
            }
        }
    }

    fun stopTicker() { job?.cancel(); job = null }

    suspend fun sweep(full: Boolean = false) {
        if (session.current() == null) return
        val expired = db.messages().expired(session.now())
        if (expired.isNotEmpty()) {
            val mediaIds = expired.mapNotNull { m -> m.mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).mediaId }.getOrNull() } }
            media.purge(mediaIds.filter { it.isNotEmpty() })
            expired.forEach { media.purgeStaged(it.stagedBlob) }
            notifier.cancelExpired(expired.mapNotNull { it.serverId })
            db.messages().delete(expired.map { it.localId })
        }
        if (full) {
            val live = db.messages().allMediaJson().mapNotNull { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).mediaId }.getOrNull() }.toSet()
            media.purgeOrphans(live)
        }
    }
}

/** Background catch-up: fetch & decrypt pending private messages, notify, and run local expiry. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val session: SessionManager,
    private val messages: Messages,
    private val expiry: Expiry,
) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("sync", "Sync", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Background synchronization"
                },
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, "sync")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Syncing")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return androidx.work.ForegroundInfo(7001, notification)
    }

    override suspend fun doWork(): Result {
        if (session.current() == null) session.load()
        if (session.current() == null) return Result.success()
        runCatching { messages.syncDirect() }
        runCatching { messages.syncHall() }
        expiry.sweep()
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "vesper-sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

/** Holds the socket open only while the app is visible; drives the expiry ticker. */
@Singleton
class AppLifecycle @Inject constructor(
    private val rt: Realtime,
    private val session: SessionManager,
    private val expiry: Expiry,
    private val notifier: Notifier,
    private val messages: Messages,
) : DefaultLifecycleObserver {
    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) {
        notifier.foreground = true
        if (session.current() != null) { rt.start(); expiry.startTicker(); messages.flushAsync() }
    }

    override fun onStop(owner: LifecycleOwner) {
        notifier.foreground = false
        rt.stop()
        expiry.stopTicker()
    }

    fun onSignedIn() {
        if (notifier.foreground) { rt.start(); expiry.startTicker() }
    }
}
