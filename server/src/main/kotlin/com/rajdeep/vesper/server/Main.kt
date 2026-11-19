package com.rajdeep.vesper.server

import com.mongodb.client.model.Updates
import com.rajdeep.vesper.shared.AckIds
import com.rajdeep.vesper.shared.AdminPresence
import com.rajdeep.vesper.shared.ApiError
import com.rajdeep.vesper.shared.AuthRequest
import com.rajdeep.vesper.shared.DeviceList
import com.rajdeep.vesper.shared.DirectAck
import com.rajdeep.vesper.shared.DirectRead
import com.rajdeep.vesper.shared.DirectSend
import com.rajdeep.vesper.shared.FcmToken
import com.rajdeep.vesper.shared.Frame
import com.rajdeep.vesper.shared.HallSend
import com.rajdeep.vesper.shared.MediaCommit
import com.rajdeep.vesper.shared.MediaIntentRequest
import com.rajdeep.vesper.shared.MuteRequest
import com.rajdeep.vesper.shared.Nack
import com.rajdeep.vesper.shared.Nacks
import com.rajdeep.vesper.shared.Ok
import com.rajdeep.vesper.shared.PasswordChange
import com.rajdeep.vesper.shared.PasswordReset
import com.rajdeep.vesper.shared.BackupKey
import com.rajdeep.vesper.shared.BackupItems
import com.rajdeep.vesper.shared.BackupIds
import com.rajdeep.vesper.shared.BackupKeyLookup
import com.rajdeep.vesper.shared.PasswordConfirm
import com.rajdeep.vesper.shared.Ping
import com.rajdeep.vesper.shared.Pong
import com.rajdeep.vesper.shared.PreKeyUpload
import com.rajdeep.vesper.shared.RefreshRequest
import com.rajdeep.vesper.shared.VesperJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import org.bson.Document
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

fun main() {
    val cfg = Config.fromEnv()
    embeddedServer(Netty, port = cfg.port, host = "0.0.0.0") { vesper(cfg, Db.connect(cfg)) }.start(wait = true)
}

class Services(val cfg: Config, val db: Db) {
    val startedAt = System.currentTimeMillis()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = Hub()
    val tokens = Tokens(cfg.jwtSecret)
    val atRest = AtRest(cfg.hallKeyset)
    val names = Names(db)
    val activity = ActivityLog(db, hub)
    val store: MediaStore = if (cfg.mediaProvider == "local") LocalMediaStore(cfg) else CloudinaryMediaStore(cfg)
    val media = MediaService(db, cfg, store)
    val accounts = Accounts(db, cfg, tokens, hub, activity, media)
    val push = Push(db, cfg)
    val hall = Hall(db, hub, atRest, media, activity, names)
    val direct = Direct(db, hub, media, activity, accounts, names, push)
    val keepAlive = KeepAlive()
    val sweeper = Sweeper(db, media, hub)
    val backups = Backups(db)
    val admin = Admin(db, hub, accounts, hall, media, activity, names, keepAlive, startedAt)
}

fun Application.vesper(cfg: Config, db: Db, startJobs: Boolean = true): Services {
    val log = LoggerFactory.getLogger("Vesper")
    val s = Services(cfg, db)
    kotlinx.coroutines.runBlocking {
        db.ensureIndexes()
        s.accounts.ensureAdmin()
    }
    s.hub.onPresence = { uid, online ->
        s.scope.launch {
            val now = ServerClock.now()
            db.users.updateOne(Document("_id", uid), Updates.set("la", now.asDate()))
            s.hub.toAdmins(AdminPresence(uid, online, now))
        }
    }
    if (startJobs) s.sweeper.start(s.scope)

    install(XForwardedHeaders)
    install(Compression) { gzip() }
    install(ContentNegotiation) { json(VesperJson) }
    install(WebSockets) {
        pingPeriod = 25.seconds
        timeout = 60.seconds
        maxFrameSize = 512L * 1024
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.code, e.message)) }
        exception<kotlinx.serialization.SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("invalid", "Malformed request")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("invalid", "Malformed request")) }
        exception<Throwable> { call, e ->
            log.error("unhandled {}: {}", e::class.simpleName, e.message)
            call.respond(HttpStatusCode.InternalServerError, ApiError("server", "Something went wrong"))
        }
    }
    install(Authentication) {
        jwt("jwt") {
            verifier(s.tokens.verifier)
            validate { cred -> if (cred.payload.getClaim("uid").asString() != null) JWTPrincipal(cred.payload) else null }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Sign in again")) }
        }
    }

    routing {
        get("/health") {
            s.keepAlive.lastPing = ServerClock.now()
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }
        route("/v1") {
            authRoutes(s)
            if (cfg.devMode) devRoutes(s)
            authenticate("jwt") {
                memberRoutes(s)
                adminRoutes(s)
                socket(s)
            }
        }
    }
    return s
}

private fun ApplicationCall.ip(): String = request.origin.remoteHost

private fun Route.authRoutes(s: Services) {
    get("/auth/available") { call.respond(s.accounts.availability(call.request.queryParameters["u"].orEmpty())) }
    post("/auth/signup") { call.respond(s.accounts.signUp(call.receive<AuthRequest>(), call.ip())) }
    post("/auth/signin") { call.respond(s.accounts.signIn(call.receive<AuthRequest>(), call.ip())) }
    post("/auth/refresh") { call.respond(s.accounts.refresh(call.receive<RefreshRequest>().refreshToken)) }
    /** Signed-out password change needs the wrapped backup key to re-wrap it; verified by username + password. */
    post("/auth/backup-key") {
        val b = call.receive<BackupKeyLookup>()
        val uid = s.accounts.verifyCredentials(b.username, b.password, call.ip())
        call.respond(s.backups.key(uid) ?: BackupKey("", 0, ""))
    }
    post("/auth/password") {
        val b = call.receive<PasswordReset>()
        val uid = s.accounts.resetPassword(b.username, b.current, b.next, call.ip())
        b.backupKey?.let { s.backups.setKey(uid, it) }
        call.respond(Ok())
    }
}

private fun Route.memberRoutes(s: Services) {
    post("/auth/signout") { s.accounts.signOut(call.caller()); call.respond(Ok()) }
    get("/me") { call.respond(s.accounts.me(call.caller())) }
    get("/me/devices") { call.respond(s.admin.devices(call.caller())) }
    post("/me/devices/{d}/signout") {
        val c = call.caller()
        val d = call.parameters["d"]?.toIntOrNull() ?: bad("device", "Bad device")
        if (d == c.did) bad("device", "Use Sign out for this phone")
        s.accounts.revokeDevice(c.uid, d, "signed_out")
        call.respond(Ok())
    }
    post("/me/devices/signout-others") {
        val c = call.caller()
        s.accounts.deviceIds(c.uid).filter { it != c.did }.forEach { s.accounts.revokeDevice(c.uid, it, "signed_out") }
        call.respond(Ok())
    }
    post("/me/password") {
        val b = call.receive<PasswordChange>()
        s.accounts.changePassword(call.caller(), b.current, b.next, b.username)
        b.backupKey?.let { s.backups.setKey(call.caller().uid, it) }
        call.respond(Ok())
    }
    post("/me/delete") {
        val c = call.caller()
        s.accounts.verifyPassword(c, call.receive<PasswordConfirm>().password)
        val hallIds = s.accounts.deleteAccount(c.uid, "deleted")
        s.names.invalidate(c.uid)
        if (hallIds.isNotEmpty()) s.hall.delete(hallIds)
        call.respond(Ok())
    }
    put("/me/fcm") { s.accounts.setFcm(call.caller(), call.receive<FcmToken>().token); call.respond(Ok()) }

    get("/users") { call.respond(s.accounts.people(call.request.queryParameters["query"], call.request.queryParameters["cursor"])) }
    get("/users/{id}") { call.respond(s.accounts.profile(call.parameters["id"]!!)) }

    get("/keys/count") { call.respond(s.accounts.preKeyCounts(call.caller())) }
    post("/keys/prekeys") { val c = call.caller(); s.accounts.storePreKeys(c.uid, c.did, call.receive<PreKeyUpload>()); call.respond(Ok()) }
    get("/keys/{userId}") { call.respond(s.accounts.bundles(call.parameters["userId"]!!)) }
    get("/keys/{userId}/devices") { val u = call.parameters["userId"]!!; call.respond(DeviceList(u, s.accounts.deviceIds(u))) }

    get("/hall") {
        val after = call.request.queryParameters["afterSeq"]?.toLongOrNull() ?: 0L
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
        call.respond(s.hall.page(after, limit))
    }
    get("/direct/pending") { call.respond(s.direct.pending(call.caller())) }
    get("/backup/key") { call.respond(s.backups.key(call.caller().uid) ?: BackupKey("", 0, "")) }
    put("/backup/key") { s.backups.setKey(call.caller().uid, call.receive<BackupKey>()); call.respond(Ok()) }
    get("/backup/items") { call.respond(s.backups.list(call.caller().uid)) }
    put("/backup/items") { s.backups.put(call.caller().uid, call.receive<BackupItems>().items); call.respond(Ok()) }
    post("/backup/delete") { s.backups.delete(call.caller().uid, call.receive<BackupIds>().ids); call.respond(Ok()) }
    post("/direct/receipts") { call.respond(s.direct.receipts(call.caller(), call.receive<com.rajdeep.vesper.shared.ReceiptQuery>())) }
    post("/direct/ack") { s.direct.ack(call.caller(), DirectAck(call.receive<AckIds>().ids)); call.respond(Ok()) }

    post("/media/intents") { call.respond(s.media.intent(call.caller(), call.receive<MediaIntentRequest>())) }
    post("/media/{id}/commit") { s.media.commit(call.caller(), call.parameters["id"]!!, call.receive<MediaCommit>()); call.respond(Ok()) }
    get("/media/{id}/url") { call.respond(s.media.url(call.caller(), call.parameters["id"]!!)) }
}

private fun RoutingContext.requireAdmin(): Caller = call.caller().also { if (!it.isAdmin) forbidden() }

private fun Route.adminRoutes(s: Services) {
    route("/admin") {
        get("/overview") { requireAdmin(); call.respond(s.admin.overview()) }
        get("/members") { requireAdmin(); call.respond(s.admin.members(call.request.queryParameters["query"])) }
        get("/members/{id}") { requireAdmin(); call.respond(s.admin.detail(call.parameters["id"]!!)) }
        post("/members/{id}/suspend") { requireAdmin(); s.admin.suspend(call.parameters["id"]!!); call.respond(Ok()) }
        post("/members/{id}/unsuspend") { requireAdmin(); s.admin.unsuspend(call.parameters["id"]!!); call.respond(Ok()) }
        post("/members/{id}/mute") { requireAdmin(); s.admin.mute(call.parameters["id"]!!, call.receive<MuteRequest>().minutes); call.respond(Ok()) }
        post("/members/{id}/unmute") { requireAdmin(); s.admin.unmute(call.parameters["id"]!!); call.respond(Ok()) }
        post("/members/{id}/signout") { requireAdmin(); s.admin.signOutAll(call.parameters["id"]!!); call.respond(Ok()) }
        delete("/members/{id}") { requireAdmin(); s.admin.delete(call.parameters["id"]!!); call.respond(Ok()) }
        get("/hall") {
            requireAdmin()
            val after = call.request.queryParameters["afterSeq"]?.toLongOrNull() ?: 0L
            call.respond(s.hall.page(after, 500, call.request.queryParameters["user"]))
        }
        delete("/hall/{id}") { requireAdmin(); s.hall.delete(listOf(call.parameters["id"]!!)); call.respond(Ok()) }
        get("/conversations") { requireAdmin(); call.respond(s.admin.conversations()) }
        get("/activity") {
            requireAdmin()
            val q = call.request.queryParameters
            call.respond(s.admin.activityPage(q["before"]?.toLongOrNull(), q["type"], q["user"]))
        }
    }
}

private fun Route.socket(s: Services) {
    webSocket("/ws") {
        val c = call.caller()
        val user = s.db.users.one(Document("_id", c.uid))
        val device = s.db.devices.one(Document("uid", c.uid).append("d", c.did))
        if (user == null || device == null || user.str("st") == "suspended") {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "ended"))
            return@webSocket
        }
        val conn = Conn(c.uid, c.did, c.isAdmin, this)
        s.hub.add(conn)
        s.accounts.touchDevice(c)
        val pump = launch { runCatching { conn.pump() } }
        var lastTouch = ServerClock.now()
        try {
            for (raw in incoming) {
                if (raw !is WsFrame.Text) continue
                val frame = runCatching { VesperJson.decodeFromString(Frame.serializer(), raw.readText()) }.getOrNull() ?: continue
                conn.lastSeen = ServerClock.now()
                val reply: Frame? = runCatching {
                    when (frame) {
                        is HallSend -> s.hall.send(c, frame)
                        is DirectSend -> s.direct.send(c, frame)
                        is DirectAck -> { s.direct.ack(c, frame); null }
                        is DirectRead -> { s.direct.read(c, frame); null }
                        is Ping -> Pong(ServerClock.now())
                        else -> null
                    }
                }.getOrElse { e ->
                    LoggerFactory.getLogger("ws").warn("frame failed: {}", e.message)
                    when (frame) {
                        is HallSend -> Nack(frame.cmid, Nacks.INVALID, "Couldn't send")
                        is DirectSend -> Nack(frame.cmid, Nacks.INVALID, "Couldn't send")
                        else -> null
                    }
                }
                reply?.let(conn::send)
                if (ServerClock.now() - lastTouch > 60_000) { lastTouch = ServerClock.now(); s.accounts.touchDevice(c) }
            }
        } finally {
            s.hub.remove(conn)
            pump.cancel()
            runCatching { s.accounts.touchDevice(c) }
        }
    }
}

/** Dev-only: local blob endpoints and a clock offset for expiry time-travel tests. Never enabled in production. */
private fun Route.devRoutes(s: Services) {
    val store = s.store as? LocalMediaStore
    if (store != null) {
        put("/devmedia/{k}") {
            val k = call.parameters["k"]!!
            val q = call.request.queryParameters
            if (q["op"] != "put" || !store.checkSig(k, "put", q["exp"]?.toLongOrNull() ?: 0, q["sig"].orEmpty())) forbidden()
            val bytes = call.receiveChannel().readRemaining(com.rajdeep.vesper.shared.Limits.MAX_MEDIA_BYTES + 1).readByteArray()
            if (bytes.size > com.rajdeep.vesper.shared.Limits.MAX_MEDIA_BYTES) bad("media", "Too large")
            store.fileFor(k.replace('_', '/')).writeBytes(bytes)
            call.respond(Ok())
        }
        get("/devmedia/{k}") {
            val k = call.parameters["k"]!!
            val q = call.request.queryParameters
            if (q["op"] != "get" || !store.checkSig(k, "get", q["exp"]?.toLongOrNull() ?: 0, q["sig"].orEmpty())) forbidden()
            val f = store.fileFor(k.replace('_', '/'))
            if (!f.exists()) notFound()
            call.respondBytes(f.readBytes(), ContentType.Application.OctetStream)
        }
    }
    post("/dev/clock") {
        val off = call.request.queryParameters["offsetMs"]?.toLongOrNull() ?: 0
        ServerClock.offsetMs = off
        s.sweeper.sweep()
        call.respond(Ok())
    }
    post("/dev/sweep") { s.sweeper.sweep(); call.respond(Ok()) }
}
