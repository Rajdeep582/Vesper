package com.rajdeep.vesper.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.shared.Ack
import com.rajdeep.vesper.shared.Frame
import com.rajdeep.vesper.shared.Nack
import com.rajdeep.vesper.shared.Ping
import com.rajdeep.vesper.shared.Pong
import com.rajdeep.vesper.shared.VesperJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

enum class Link { OFFLINE, CONNECTING, ONLINE }

/**
 * Single WebSocket held while the app is in the foreground. Reconnects with jittered backoff (0.5 s → 30 s)
 * and immediately when the network comes back. Every (re)connect emits [connected] so repositories resync.
 */
@Singleton
class Realtime @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: Api,
    private val session: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _link = MutableStateFlow(Link.OFFLINE)
    val link: StateFlow<Link> = _link
    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    val frames: SharedFlow<Frame> = _frames
    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connected: SharedFlow<Unit> = _connected
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Frame>>()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    private var loop: Job? = null
    private var attempt = 0
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { attempt = 0; wake.tryEmit(Unit) }
            },
        )
    }

    fun start() {
        wanted = true
        if (loop?.isActive == true) { wake.tryEmit(Unit); return }
        loop = scope.launch { run() }
    }

    fun stop() {
        wanted = false
        loop?.cancel()
        ws?.close(1000, "background")
        ws = null
        _link.value = Link.OFFLINE
    }

    private suspend fun run() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive && wanted) {
            if (session.current() == null) { delay(1000); continue }
            val closed = CompletableDeferred<Unit>()
            _link.value = Link.CONNECTING
            connectOnce(closed)
            closed.await()
            ws = null
            _link.value = Link.OFFLINE
            if (!wanted) break
            attempt++
            val backoff = minOf(30_000L, (500L * (1 shl minOf(attempt, 6)))).let { it / 2 + Random.nextLong(it / 2 + 1) }
            withTimeoutOrNull(backoff) { wake.first() }
        }
    }

    private suspend fun connectOnce(closed: CompletableDeferred<Unit>) {
        // Proactively refresh an expiring access token so the upgrade isn't rejected.
        val s = session.current() ?: run { closed.complete(Unit); return }
        if (s.accessExpiresAt - 60_000 < System.currentTimeMillis()) {
            if (!api.refresh(s.accessToken)) { closed.complete(Unit); return }
        }
        val token = session.accessToken() ?: run { closed.complete(Unit); return }
        val url = api.base.replaceFirst("http", "ws") + "/v1/ws"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        ws = api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                _link.value = Link.ONLINE
                _connected.tryEmit(Unit)
                webSocket.send(VesperJson.encodeToString(Frame.serializer(), Ping(System.currentTimeMillis())))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val f = runCatching { VesperJson.decodeFromString(Frame.serializer(), text) }.getOrNull() ?: return
                when (f) {
                    is Ack -> waiters.remove(f.cmid)?.complete(f)
                    is Nack -> waiters.remove(f.cmid)?.complete(f)
                    is Pong -> scope.launch { session.updateSkew(f.serverTime) }
                    else -> {}
                }
                _frames.tryEmit(f)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(); closed.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) scope.launch { api.refresh(token) }
                fail(); closed.complete(Unit)
            }
        })
    }

    private fun fail() {
        waiters.values.forEach { it.cancel() }
        waiters.clear()
    }

    /** Sends a frame and waits for its ack/nack (keyed by cmid). Returns null on timeout or no socket. */
    suspend fun request(cmid: String, frame: Frame, timeoutMs: Long = 15_000): Frame? {
        val socket = ws ?: return null
        if (_link.value != Link.ONLINE) return null
        val d = CompletableDeferred<Frame>()
        waiters[cmid] = d
        if (!socket.send(VesperJson.encodeToString(Frame.serializer(), frame))) { waiters.remove(cmid); return null }
        return runCatching { withTimeoutOrNull(timeoutMs) { d.await() } }.getOrNull().also { waiters.remove(cmid) }
    }

    fun send(frame: Frame): Boolean = ws?.send(VesperJson.encodeToString(Frame.serializer(), frame)) == true
}
