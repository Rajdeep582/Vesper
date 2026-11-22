package com.rajdeep.vesper.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rajdeep.vesper.BuildConfig
import com.rajdeep.vesper.core.net.Api
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.shared.FcmToken
import com.rajdeep.vesper.shared.Ok
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content-free push. The server sends {"t":"wake"}; we fetch and decrypt over our own channel.
 * Firebase is initialised manually from BuildConfig so no google-services.json is needed; blank config = disabled.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: Api,
    private val session: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val enabled get() = BuildConfig.FCM_APP_ID.isNotBlank() && BuildConfig.FCM_PROJECT_ID.isNotBlank()

    fun init() {
        if (!enabled || FirebaseApp.getApps(ctx).isNotEmpty()) return
        FirebaseApp.initializeApp(
            ctx,
            FirebaseOptions.Builder().setApplicationId(BuildConfig.FCM_APP_ID).setApiKey(BuildConfig.FCM_API_KEY)
                .setProjectId(BuildConfig.FCM_PROJECT_ID).setGcmSenderId(BuildConfig.FCM_SENDER_ID).build(),
        )
    }

    fun register(token: String? = null) {
        if (!enabled || session.current() == null) return
        scope.launch {
            runCatching {
                val t = token ?: FirebaseMessaging.getInstance().token.await()
                api.put("/v1/me/fcm", FcmToken(t), FcmToken.serializer(), Ok.serializer())
            }
        }
    }

    companion object {
        fun wake(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "vesper-wake", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().apply {
                    if (android.os.Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }.build(),
            )
        }
    }
}

@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var messages: Messages
    @Inject lateinit var session: SessionManager
    @Inject lateinit var expiry: Expiry

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] == "wake") {
            // Fetch right here (FCM gives us ~20 s); fall back to a background job only if that didn't finish,
            // instead of always fetching twice.
            val done = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(15_000L) {
                        if (session.current() == null) session.load()
                        if (session.current() != null) {
                            kotlinx.coroutines.coroutineScope {
                                launch { messages.syncDirect() }
                                launch { messages.syncHall() }
                            }
                            expiry.sweep()
                            if (messages.hasPending()) OutboxWorker.kick(applicationContext)
                        }
                        true
                    } ?: false
                }
            }.getOrDefault(false)
            if (!done) PushRegistrar.wake(applicationContext)
        }
    }

    override fun onNewToken(token: String) = registrar.register(token)
}
