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
            NotificationChannel(CHANNEL_MENTION, "Mentions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone @mentions you or @all in the Hall"
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

    private fun allowed() = !(android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
    private fun preview(kind: MessageKind, text: String?) = when (kind) { MessageKind.TEXT -> text?.take(300) ?: ""; MessageKind.VOICE -> "🎤 Voice message"; MessageKind.IMAGE -> "📷 Photo"; MessageKind.FILE -> "📎 File" }
    private val me = androidx.core.app.Person.Builder().setName("You").setKey("me").build()

    /** Continues the conversation already on screen (WhatsApp-style stacked lines), or starts a new one. */
    private fun styleFor(notifId: Int, title: String?, group: Boolean): NotificationCompat.MessagingStyle {
        val sys = ctx.getSystemService(NotificationManager::class.java)
        val old = runCatching { sys.activeNotifications.firstOrNull { it.id == notifId }?.notification }.getOrNull()
        val st = old?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) } ?: NotificationCompat.MessagingStyle(me)
        st.conversationTitle = title
        st.isGroupConversation = group
        while (st.messages.size > MAX_LINES) st.messages.removeAt(0)
        return st
    }

    private fun actionIntent(action: String, scope: String, peer: String?, notifId: Int, mutable: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, (action + scope + (peer ?: "")).hashCode(),
            Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
                .putExtra(EXTRA_SCOPE, scope).putExtra(EXTRA_PEER, peer).putExtra(EXTRA_NOTIF, notifId),
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun replyAction(scope: String, peer: String?, notifId: Int, label: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", actionIntent(ACTION_REPLY, scope, peer, notifId, mutable = true))
            .addRemoteInput(androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()

    private fun readAction(scope: String, peer: String?, notifId: Int): NotificationCompat.Action =
        NotificationCompat.Action.Builder(R.drawable.ic_notification, "Mark as read", actionIntent(ACTION_READ, scope, peer, notifId, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

    private fun openIntent(peer: String?, code: Int) = PendingIntent.getActivity(
        ctx, code,
        Intent(ctx, MainActivity::class.java).apply { peer?.let { putExtra(MainActivity.EXTRA_PEER, it) } }.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun direct(peer: String, name: String, kind: MessageKind, text: String?, mid: String, expiresAt: Long) {
        if (isViewing(peer)) return
        if (settings.isTurnOffInboxNotifications() || settings.isPeerMuted(peer)) return
        if (!allowed()) return
        val id = peer.hashCode()
        val sender = androidx.core.app.Person.Builder().setName(name).setKey(peer).build()
        val st = styleFor(id, null, false).addMessage(preview(kind, text), System.currentTimeMillis(), sender)
        val n = NotificationCompat.Builder(ctx, CHANNEL_DIRECT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8FA3C8.toInt())
            .setStyle(st)
            .setContentTitle(name)
            .setContentText(preview(kind, text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setGroup("direct")
            .setTimeoutAfter((expiresAt - System.currentTimeMillis()).coerceAtLeast(1000))
            .setContentIntent(openIntent(peer, id))
            .addAction(replyAction(com.rajdeep.vesper.core.db.Scope.DIRECT, peer, id, "Reply to $name"))
            .addAction(readAction(com.rajdeep.vesper.core.db.Scope.DIRECT, peer, id))
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
        byMessage[mid] = id to expiresAt
    }

    fun hall(name: String, kind: MessageKind, text: String?, id: String, expiresAt: Long, mention: Boolean = false, everyone: Boolean = false) {
        if (isViewingHall()) return
        if (mention) { mention(name, text, id, expiresAt, everyone); return }
        if (settings.isTurnOffHallNotifications()) return
        if (!allowed()) return
        val notifId = HALL_ID
        val sender = androidx.core.app.Person.Builder().setName(name).setKey("hall:$name").build()
        val st = styleFor(notifId, "The Hall", true).addMessage(preview(kind, text), System.currentTimeMillis(), sender)
        val n = NotificationCompat.Builder(ctx, CHANNEL_HALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFD4A853.toInt())
            .setStyle(st)
            .setContentTitle("The Hall")
            .setContentText("$name: " + preview(kind, text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setTimeoutAfter((expiresAt - System.currentTimeMillis()).coerceAtLeast(1000))
            .setContentIntent(openIntent(null, notifId))
            .addAction(replyAction(com.rajdeep.vesper.core.db.Scope.HALL, null, notifId, "Message the Hall"))
            .addAction(readAction(com.rajdeep.vesper.core.db.Scope.HALL, null, notifId))
            .build()
        NotificationManagerCompat.from(ctx).notify(notifId, n)
        byMessage[id] = notifId to expiresAt
    }

    /**
     * After an inline reply: show "You: …" in the same notification, silently (no sound/vibration), and stop the
     * spinner Android shows on the reply field. The chat itself updates from the database.
     */
    fun ownReply(notifId: Int, text: String) {
        if (!allowed()) return
        val sys = ctx.getSystemService(NotificationManager::class.java)
        val old = runCatching { sys.activeNotifications.firstOrNull { it.id == notifId }?.notification }.getOrNull() ?: return
        val st = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(old) ?: NotificationCompat.MessagingStyle(me)
        st.addMessage(text, System.currentTimeMillis(), null as androidx.core.app.Person?)
        while (st.messages.size > MAX_LINES) st.messages.removeAt(0)
        val b = NotificationCompat.Builder(ctx, old)
            .setStyle(st)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        NotificationManagerCompat.from(ctx).notify(notifId, b.build())
    }

    fun cancel(notifId: Int) {
        NotificationManagerCompat.from(ctx).cancel(notifId)
        byMessage.entries.removeIf { it.value.first == notifId }
    }

    /** @mention / @all: own channel, one notification per message, and it ignores every mute (like WhatsApp). */
    private fun mention(name: String, text: String?, id: String, expiresAt: Long, everyone: Boolean) {
        if (!allowed()) return
        val notifId = MENTION_BASE + (id.hashCode() and 0xFFFF)
        val title = if (everyone) "$name @all in the Hall" else "$name mentioned you in the Hall"
        val body = text?.take(300) ?: "Replied to your message"
        val sender = androidx.core.app.Person.Builder().setName(name).setKey("hall:$name").build()
        val st = NotificationCompat.MessagingStyle(me).setConversationTitle(title).setGroupConversation(true)
            .addMessage(body, System.currentTimeMillis(), sender)
        val n = NotificationCompat.Builder(ctx, CHANNEL_MENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFD4A853.toInt())
            .setStyle(st)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setGroup("mentions")
            .setTimeoutAfter((expiresAt - System.currentTimeMillis()).coerceAtLeast(1000))
            .setContentIntent(openIntent(null, 7003))
            .addAction(replyAction(com.rajdeep.vesper.core.db.Scope.HALL, null, notifId, "Reply in the Hall"))
            .build()
        NotificationManagerCompat.from(ctx).notify(notifId, n)
        byMessage[id] = notifId to expiresAt
    }

    fun cancelFor(peer: String) = NotificationManagerCompat.from(ctx).cancel(peer.hashCode())
    fun cancelHall() {
        NotificationManagerCompat.from(ctx).cancel(7002)
        val sys = ctx.getSystemService(NotificationManager::class.java)
        runCatching { sys.activeNotifications.filter { it.notification.channelId == CHANNEL_MENTION }.forEach { sys.cancel(it.id) } }
        byMessage.entries.removeIf { it.value.first == 7002 || it.value.first >= MENTION_BASE }
    }

    fun cancelDirectAll() {
        // Also clears notifications posted by an earlier process (byMessage only knows this one's).
        val sys = ctx.getSystemService(NotificationManager::class.java)
        runCatching { sys.activeNotifications.filter { it.notification.channelId == CHANNEL_DIRECT }.forEach { sys.cancel(it.id) } }
        byMessage.entries.removeIf { it.value.first != 7002 && it.value.first < MENTION_BASE }
    }

    fun cancelExpired(mids: Collection<String>) {
        mids.forEach { mid -> byMessage.remove(mid)?.let { NotificationManagerCompat.from(ctx).cancel(it.first) } }
    }

    fun cancelAll() = NotificationManagerCompat.from(ctx).cancelAll()

    companion object {
        const val CHANNEL_DIRECT = "vesper_direct"
        const val CHANNEL_HALL = "vesper_hall"
        const val CHANNEL_MENTION = "vesper_mention"
        const val MENTION_BASE = 0x7100000
        const val HALL_ID = 7002
        const val KEY_REPLY = "vesper_reply"
        const val ACTION_REPLY = "com.rajdeep.vesper.REPLY"
        const val ACTION_READ = "com.rajdeep.vesper.MARK_READ"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_PEER = "peer"
        const val EXTRA_NOTIF = "notif"
        private const val MAX_LINES = 8
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
            val mediaIds = expired.flatMap { m -> m.mediaJson?.let { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).blobIds() }.getOrNull() } ?: emptyList() }
            media.purge(mediaIds)
            expired.forEach { media.purgeStaged(it.stagedBlob) }
            notifier.cancelExpired(expired.mapNotNull { it.serverId })
            db.messages().delete(expired.map { it.localId })
        }
        if (full) {
            runCatching { db.kv().purgeHallTombstones(session.now()) }
            val live = db.messages().allMediaJson().flatMap { runCatching { VesperJson.decodeFromString(MediaRef.serializer(), it).blobIds() }.getOrDefault(emptyList()) }.toSet()
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
    @ApplicationContext private val ctx: Context,
    private val rt: Realtime,
    private val session: SessionManager,
    private val expiry: Expiry,
    private val notifier: Notifier,
    private val messages: Messages,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) {
        notifier.foreground = true
        if (session.current() != null) { rt.start(); expiry.startTicker(); messages.flushAsync() }
    }

    override fun onStop(owner: LifecycleOwner) {
        notifier.foreground = false
        rt.stop()
        expiry.stopTicker()
        // Anything still in the outbox (sent just before leaving, or offline) is delivered in the background.
        scope.launch { if (session.current() != null && messages.hasPending()) OutboxWorker.kick(ctx) }
    }

    fun onSignedIn() {
        if (notifier.foreground) { rt.start(); expiry.startTicker() }
    }
}
