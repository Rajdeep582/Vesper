package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.Frame
import com.rajdeep.vesper.shared.VesperJson
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** One live socket. Outbound frames go through a bounded channel so a slow client can't block fan-out. */
class Conn(val uid: String, val did: Int, val isAdmin: Boolean, val ws: WebSocketSession) {
    val out = Channel<String>(capacity = 512)
    @Volatile var lastSeen: Long = ServerClock.now()

    fun send(frame: Frame) {
        if (out.trySend(VesperJson.encodeToString(Frame.serializer(), frame)).isFailure) {
            // Client can't keep up; drop the socket, it will reconnect and resync.
            out.close()
        }
    }

    suspend fun pump() {
        for (text in out) ws.send(WsFrame.Text(text))
    }
}

/** Registry of live sockets. Presence = has at least one socket. */
class Hub {
    private val byUser = ConcurrentHashMap<String, MutableSet<Conn>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var onPresence: (uid: String, online: Boolean) -> Unit = { _, _ -> }

    fun add(c: Conn) {
        val set = byUser.computeIfAbsent(c.uid) { ConcurrentHashMap.newKeySet() }
        val wasOnline = set.isNotEmpty()
        set += c
        if (!wasOnline) onPresence(c.uid, true)
    }

    fun remove(c: Conn) {
        val set = byUser[c.uid] ?: return
        set -= c
        if (set.isEmpty()) {
            byUser.remove(c.uid, set)
            onPresence(c.uid, false)
        }
    }

    fun isOnline(uid: String) = byUser[uid]?.isNotEmpty() == true
    fun onlineCount() = byUser.size
    fun onlineUsers(): Set<String> = byUser.keys.toSet()

    fun toUser(uid: String, frame: Frame) = byUser[uid]?.forEach { it.send(frame) }
    fun toDevice(uid: String, did: Int, frame: Frame) = byUser[uid]?.filter { it.did == did }?.forEach { it.send(frame) }
    fun toAll(frame: Frame) = byUser.values.forEach { set -> set.forEach { it.send(frame) } }
    fun toAdmins(frame: Frame) = byUser.values.forEach { set -> set.filter { it.isAdmin }.forEach { it.send(frame) } }

    fun kickUser(uid: String, reason: Frame, deviceId: Int? = null) {
        byUser[uid]?.filter { deviceId == null || it.did == deviceId }?.forEach { c ->
            c.send(reason)
            scope.launch { runCatching { c.ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "ended")) } }
        }
    }
}
