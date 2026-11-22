package com.rajdeep.vesper.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rajdeep.vesper.R
import com.rajdeep.vesper.core.db.Scope
import com.rajdeep.vesper.core.net.Link
import com.rajdeep.vesper.core.net.Realtime
import com.rajdeep.vesper.core.session.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Reply / Mark as read straight from a notification, without opening the app. The reply is written to the local
 * outbox at once (so the chat shows it immediately) and [OutboxWorker] delivers it even when the app is closed.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var messages: Messages
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var session: SessionManager

    override fun onReceive(context: Context, intent: Intent) {
        val scope = intent.getStringExtra(Notifier.EXTRA_SCOPE) ?: return
        val peer = intent.getStringExtra(Notifier.EXTRA_PEER)
        val notifId = intent.getIntExtra(Notifier.EXTRA_NOTIF, 0)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (session.current() == null) session.load()
                if (session.current() == null) { notifier.cancel(notifId); return@launch }
                when (intent.action) {
                    Notifier.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                        if (text.isNullOrEmpty()) { notifier.ownReply(notifId, ""); return@launch }
                        messages.queueText(scope, peer, text)
                        notifier.ownReply(notifId, text)
                        if (scope == Scope.DIRECT && peer != null) messages.markRead(peer)
                        OutboxWorker.kick(context)
                    }
                    Notifier.ACTION_READ -> {
                        if (scope == Scope.DIRECT && peer != null) messages.markRead(peer)
                        notifier.cancel(notifId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Delivers queued messages (and read receipts) when the app isn't on screen: opens the socket just long enough,
 * flushes the outbox, then closes it again unless the app came to the foreground meanwhile.
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val session: SessionManager,
    private val messages: Messages,
    private val rt: Realtime,
    private val notifier: Notifier,
) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("sync", "Sync", android.app.NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(applicationContext, "sync")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Sending…").setPriority(NotificationCompat.PRIORITY_MIN).build()
        return androidx.work.ForegroundInfo(7004, n)
    }

    override suspend fun doWork(): Result {
        if (session.current() == null) session.load()
        if (session.current() == null) return Result.success()
        if (!messages.hasPending()) return Result.success()
        val wasOnline = rt.link.value == Link.ONLINE
        if (!wasOnline) rt.start()
        val up = withTimeoutOrNull(25_000) { rt.link.first { it == Link.ONLINE } } != null
        if (up) messages.flush()
        if (!notifier.foreground && !wasOnline) rt.stop()
        return if (messages.hasPending()) Result.retry() else Result.success()
    }

    companion object {
        fun kick(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "vesper-outbox", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<OutboxWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
