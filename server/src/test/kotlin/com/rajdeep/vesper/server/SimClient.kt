package com.rajdeep.vesper.server

import com.rajdeep.vesper.shared.Ack
import com.rajdeep.vesper.shared.AckIds
import com.rajdeep.vesper.shared.AuthRequest
import com.rajdeep.vesper.shared.AuthResponse
import com.rajdeep.vesper.shared.DeviceList
import com.rajdeep.vesper.shared.DeviceRegistration
import com.rajdeep.vesper.shared.DirectAck
import com.rajdeep.vesper.shared.DirectEnv
import com.rajdeep.vesper.shared.DirectPayload
import com.rajdeep.vesper.shared.DirectSend
import com.rajdeep.vesper.shared.Frame
import com.rajdeep.vesper.shared.HallMsg
import com.rajdeep.vesper.shared.HallSend
import com.rajdeep.vesper.shared.KeysResponse
import com.rajdeep.vesper.shared.KyberPreKeyDto
import com.rajdeep.vesper.shared.MessageBody
import com.rajdeep.vesper.shared.MessageKind
import com.rajdeep.vesper.shared.Nack
import com.rajdeep.vesper.shared.OutEnvelope
import com.rajdeep.vesper.shared.PendingEnvelopes
import com.rajdeep.vesper.shared.PreKeyDto
import com.rajdeep.vesper.shared.SignedPreKeyDto
import com.rajdeep.vesper.shared.UserPage
import com.rajdeep.vesper.shared.VesperJson
import kotlinx.serialization.KSerializer
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A second Vesper client for device testing (real libsignal, real protocol).
 * Usage: ./gradlew -p server-build :server:sim --args="<base> <user> <password> <command...>"
 *   hall <text> | dm <username> <text> | listen <seconds> [echo]
 */
class SimClient(private val base: String) {
    private val http = HttpClient.newHttpClient()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    lateinit var auth: AuthResponse
    private lateinit var store: StringKeyedStore
    private val inbox = LinkedBlockingQueue<Frame>()
    private var ws: WebSocket? = null

    private fun <R> req(method: String, path: String, body: String?, out: KSerializer<R>, token: Boolean = true): R {
        val b = HttpRequest.newBuilder(URI(base + path)).header("Content-Type", "application/json")
        if (token) b.header("Authorization", "Bearer ${auth.accessToken}")
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        check(r.statusCode() in 200..299) { "HTTP ${r.statusCode()} $path: ${r.body()}" }
        return VesperJson.decodeFromString(out, r.body())
    }

    fun login(user: String, pw: String) {
        val id = IdentityKeyPair.generate()
        val reg = 1 + (Math.random() * 16000).toInt()
        store = StringKeyedStore(id, reg)
        val spk = ECKeyPair.generate()
        val spkRec = SignedPreKeyRecord(1, System.currentTimeMillis(), spk, id.privateKey.calculateSignature(spk.publicKey.serialize()))
        store.storeSignedPreKey(1, spkRec)
        fun ky(i: Int): KyberPreKeyRecord { val k = KEMKeyPair.generate(KEMKeyType.KYBER_1024); return KyberPreKeyRecord(i, System.currentTimeMillis(), k, id.privateKey.calculateSignature(k.publicKey.serialize())) }
        val last = ky(1).also { store.storeKyberPreKey(1, it) }
        val pres = (1..20).map { PreKeyRecord(it, ECKeyPair.generate()).also { r -> store.storePreKey(it, r) } }
        val kys = (2..21).map { ky(it).also { r -> store.storeKyberPreKey(it, r) } }
        val dr = DeviceRegistration(
            reg, b64e.encodeToString(id.publicKey.serialize()),
            SignedPreKeyDto(1, b64e.encodeToString(spk.publicKey.serialize()), b64e.encodeToString(spkRec.signature)),
            KyberPreKeyDto(1, b64e.encodeToString(last.keyPair.publicKey.serialize()), b64e.encodeToString(last.signature)),
            pres.map { PreKeyDto(it.id, b64e.encodeToString(it.keyPair.publicKey.serialize())) },
            kys.map { KyberPreKeyDto(it.id, b64e.encodeToString(it.keyPair.publicKey.serialize()), b64e.encodeToString(it.signature)) },
            "sim",
        )
        val body = VesperJson.encodeToString(AuthRequest.serializer(), AuthRequest(user, pw, dr))
        auth = runCatching { req("POST", "/v1/auth/signin", body, AuthResponse.serializer(), token = false) }
            .getOrElse { req("POST", "/v1/auth/signup", body, AuthResponse.serializer(), token = false) }
        println("signed in as ${auth.user.username} device ${auth.deviceId}")
    }

    fun connect() {
        val wsUrl = base.replaceFirst("http", "ws") + "/v1/ws"
        ws = http.newWebSocketBuilder().header("Authorization", "Bearer ${auth.accessToken}").buildAsync(URI(wsUrl), object : WebSocket.Listener {
            val sb = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                sb.append(data)
                if (last) { runCatching { inbox.put(VesperJson.decodeFromString(Frame.serializer(), sb.toString())) }; sb.clear() }
                webSocket.request(1); return null
            }
        }).get(10, TimeUnit.SECONDS)
    }

    private fun send(f: Frame) { ws!!.sendText(VesperJson.encodeToString(Frame.serializer(), f), true).get() }

    private fun await(cmid: String): Frame {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val f = inbox.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (f is Ack && f.cmid == cmid || f is Nack && f.cmid == cmid) return f
            handle(f, echo = false)
        }
        error("no ack")
    }

    /** Typing pulse: "typing" on, refreshed every 3 s for [seconds], then off. toUser null = the Hall. */
    fun typing(toUsername: String?, seconds: Int) {
        val to = toUsername?.let { userId(it) }
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            send(com.rajdeep.vesper.shared.Typing(to, true)); println("typing -> ${toUsername ?: "hall"}")
            val until = minOf(end, System.currentTimeMillis() + 3_000)
            while (System.currentTimeMillis() < until) inbox.poll(200, TimeUnit.MILLISECONDS)?.let { handle(it, false) }
        }
        send(com.rajdeep.vesper.shared.Typing(to, false)); println("typing off")
    }

    fun hall(text: String) {
        val cmid = UUID.randomUUID().toString()
        send(HallSend(cmid, MessageKind.TEXT, MessageBody(text)))
        println("hall -> ${await(cmid)}")
    }

    private fun userId(username: String): String =
        req("GET", "/v1/users?query=$username", null, UserPage.serializer()).items.first { it.username == username }.id

    fun dm(username: String, text: String) = dmTo(userId(username), text)

    fun dmTo(peer: String, text: String) {
        val me = auth.user.id
        val cmid = UUID.randomUUID().toString()
        val payload = VesperJson.encodeToString(DirectPayload.serializer(), DirectPayload(cmid, MessageKind.TEXT, MessageBody(text), me, peer, System.currentTimeMillis())).toByteArray()
        val local = SignalProtocolAddress(me, auth.deviceId)
        val targets = req("GET", "/v1/keys/$peer/devices", null, DeviceList.serializer()).deviceIds.map { peer to it } +
            req("GET", "/v1/keys/$me/devices", null, DeviceList.serializer()).deviceIds.filter { it != auth.deviceId }.map { me to it }
        targets.map { it.first }.toSet().forEach { u ->
            if (targets.any { (uu, d) -> uu == u && !store.containsSession(SignalProtocolAddress(u, d)) }) {
                req("GET", "/v1/keys/$u", null, KeysResponse.serializer()).devices.forEach { b ->
                    val addr = SignalProtocolAddress(u, b.deviceId)
                    if (store.containsSession(addr) || (u == me && b.deviceId == auth.deviceId)) return@forEach
                    println("bundle $u/${b.deviceId} reg=${b.registrationId} pre=${b.preKey?.id} ky=${b.kyberPreKey.id}")
                    SessionBuilder(store, addr, local).process(
                        PreKeyBundle(
                            b.registrationId, b.deviceId, b.preKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID, b.preKey?.let { ECPublicKey(b64d.decode(it.publicKey)) },
                            b.signedPreKey.id, ECPublicKey(b64d.decode(b.signedPreKey.publicKey)), b64d.decode(b.signedPreKey.signature),
                            IdentityKey(b64d.decode(b.identityKey)), b.kyberPreKey.id, KEMPublicKey(b64d.decode(b.kyberPreKey.publicKey)), b64d.decode(b.kyberPreKey.signature),
                        ),
                    )
                }
            }
        }
        targets.forEach { (u, d) -> println("target $u/$d session=${store.containsSession(SignalProtocolAddress(u, d))}") }
        val envs = targets.map { (u, d) ->
            val c = SessionCipher(store, local, SignalProtocolAddress(u, d)).encrypt(payload)
            OutEnvelope(u, d, c.type, b64e.encodeToString(c.serialize()))
        }
        send(DirectSend(cmid, peer, MessageKind.TEXT, null, envs))
        println("dm -> ${await(cmid)}")
    }

    private fun handle(f: Frame, echo: Boolean) {
        when (f) {
            is HallMsg -> println("[hall] ${f.msg.senderName}: ${f.msg.body.text ?: f.msg.kind}")
            is DirectEnv -> {
                val e = f.env
                val c = SessionCipher(store, SignalProtocolAddress(auth.user.id, auth.deviceId), SignalProtocolAddress(e.fromUserId, e.fromDeviceId))
                val bytes = b64d.decode(e.body)
                val plain = runCatching {
                    if (e.type == CiphertextMessage.PREKEY_TYPE) c.decrypt(PreKeySignalMessage(bytes)) else c.decrypt(SignalMessage(bytes))
                }.getOrElse { println("[dm] decrypt failed: ${it.message}"); null }
                send(DirectAck(listOf(e.id)))
                plain?.let {
                    val p = VesperJson.decodeFromString(DirectPayload.serializer(), String(it))
                    println("[dm] from ${p.fromUserId}: ${p.body.text ?: p.kind} (expires ${e.expiresAt})")
                    if (echo && p.kind == MessageKind.TEXT && p.fromUserId != auth.user.id) dmTo(p.fromUserId, "echo: " + p.body.text)
                }
            }
            else -> println("[frame] $f")
        }
    }

    fun listen(seconds: Int, echo: Boolean) {
        val pending = req("GET", "/v1/direct/pending", null, PendingEnvelopes.serializer())
        pending.items.forEach { handle(DirectEnv(it), echo) }
        if (pending.items.isNotEmpty()) req("POST", "/v1/direct/ack", VesperJson.encodeToString(AckIds.serializer(), AckIds(pending.items.map { it.id })), com.rajdeep.vesper.shared.Ok.serializer())
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) inbox.poll(500, TimeUnit.MILLISECONDS)?.let { handle(it, echo) }
    }
}

fun main(args: Array<String>) {
    val (base, user, pw) = args
    val sim = SimClient(base)
    sim.login(user, pw)
    sim.connect()
    var i = 3
    while (i < args.size) {
        when (args[i]) {
            "hall" -> { sim.hall(args[i + 1]); i += 2 }
            "dm" -> { sim.dm(args[i + 1], args[i + 2]); i += 3 }
            "type" -> { sim.typing(args[i + 1], args[i + 2].toInt()); i += 3 }
            "typehall" -> { sim.typing(null, args[i + 1].toInt()); i += 2 }
            "listen" -> { val echo = args.getOrNull(i + 2) == "echo"; sim.listen(args[i + 1].toInt(), echo); i += if (echo) 3 else 2 }
            else -> error("unknown command ${args[i]}")
        }
    }
    System.exit(0)
}
