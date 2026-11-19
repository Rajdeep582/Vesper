package com.rajdeep.vesper.server

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.rajdeep.vesper.shared.AccountEnded
import com.rajdeep.vesper.shared.AuthRequest
import com.rajdeep.vesper.shared.AuthResponse
import com.rajdeep.vesper.shared.Availability
import com.rajdeep.vesper.shared.DeviceBundle
import com.rajdeep.vesper.shared.DeviceRegistration
import com.rajdeep.vesper.shared.KeysResponse
import com.rajdeep.vesper.shared.KyberPreKeyDto
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.PreKeyCounts
import com.rajdeep.vesper.shared.PreKeyDto
import com.rajdeep.vesper.shared.PreKeyUpload
import com.rajdeep.vesper.shared.SignedPreKeyDto
import com.rajdeep.vesper.shared.TokenPair
import com.rajdeep.vesper.shared.UserDto
import com.rajdeep.vesper.shared.UserPage
import com.rajdeep.vesper.shared.UserSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.bson.Document
import org.slf4j.LoggerFactory

class Accounts(
    private val db: Db,
    private val cfg: Config,
    private val tokens: Tokens,
    private val hub: Hub,
    private val activity: ActivityLog,
    private val media: MediaService,
) {
    private val log = LoggerFactory.getLogger("Accounts")
    private val signInLimiter = RateLimiter(5, 15 * 60 * 1000)
    private val signUpLimiter = RateLimiter(10, 60 * 60 * 1000)

    suspend fun ensureAdmin() {
        val admin = db.users.one(Document("role", "admin"))
        if (admin == null) {
            val clash = db.users.one(Document("u", cfg.adminUsername))
            check(clash == null) { "ADMIN_USERNAME '${cfg.adminUsername}' is already taken by a member account" }
            val now = ServerClock.now()
            db.users.insertOne(
                doc(
                    "_id" to randomId(), "u" to cfg.adminUsername, "ph" to cfg.adminPasswordHash, "seed" to cfg.adminPasswordHash,
                    "role" to "admin", "st" to "active", "at" to now.asDate(), "la" to now.asDate(),
                ),
            )
            log.info("Seeded the admin identity")
            return
        }
        val updates = mutableListOf<org.bson.conversions.Bson>()
        if (admin.str("u") != cfg.adminUsername) {
            check(db.users.one(Document("u", cfg.adminUsername)) == null) { "ADMIN_USERNAME is taken" }
            updates += Updates.set("u", cfg.adminUsername)
        }
        // Env hash changed => operator rotated the admin password; otherwise keep any in-app change.
        if (admin.str("seed") != cfg.adminPasswordHash) {
            updates += Updates.set("ph", cfg.adminPasswordHash)
            updates += Updates.set("seed", cfg.adminPasswordHash)
        }
        if (updates.isNotEmpty()) db.users.updateOne(Document("_id", admin.str("_id")), Updates.combine(updates))
    }

    suspend fun availability(raw: String): Availability {
        val u = raw.trim().lowercase()
        Limits.usernameProblem(u)?.let { return Availability(false, it) }
        if (u == cfg.adminUsername) return Availability(false, "That name is reserved")
        return if (db.users.one(Document("u", u)) == null) Availability(true) else Availability(false, "Already taken")
    }

    suspend fun signUp(req: AuthRequest, ip: String): AuthResponse {
        if (!signUpLimiter.allow(ip)) tooMany()
        val u = req.username.trim().lowercase()
        Limits.usernameProblem(u)?.let { bad("username", it) }
        if (u == cfg.adminUsername) bad("username", "That name is reserved")
        Limits.passwordProblem(req.password)?.let { bad("password", it) }
        if (CommonPasswords.contains(req.password)) bad("password", "That password is too common")
        validateRegistration(req.device)
        val now = ServerClock.now()
        val uid = randomId()
        val hash = withContext(Dispatchers.Default) { Passwords.hash(req.password) }
        try {
            db.users.insertOne(doc("_id" to uid, "u" to u, "ph" to hash, "role" to "member", "st" to "active", "at" to now.asDate(), "la" to now.asDate()))
        } catch (e: com.mongodb.MongoWriteException) {
            if (e.error.code == 11000) bad("username", "Already taken") else throw e
        }
        val did = registerDevice(uid, req.device)
        activity.record("signup", uid, u)
        return issue(uid, u, "member", did, now.asDate().time)
    }

    suspend fun signIn(req: AuthRequest, ip: String): AuthResponse {
        val u = req.username.trim().lowercase()
        val key = "$u|$ip"
        if (!signInLimiter.allow(key)) tooMany()
        val user = db.users.one(Document("u", u))
        val ok = withContext(Dispatchers.Default) { Passwords.verify(req.password, user?.str("ph")) }
        if (user == null || !ok) unauthorized("Wrong username or password")
        if (user.str("st") == "suspended") forbidden("suspended", "This account is suspended")
        validateRegistration(req.device)
        signInLimiter.reset(key)
        val uid = user.str("_id")!!
        val did = registerDevice(uid, req.device)
        activity.record("signin", uid, u)
        return issue(uid, u, user.str("role") ?: "member", did, user.millis("at") ?: 0)
    }

    private suspend fun issue(uid: String, username: String, role: String, did: Int, joinedAt: Long): AuthResponse {
        val now = ServerClock.now()
        val (access, exp) = tokens.access(uid, did, role, now)
        val refresh = Tokens.newRefresh()
        db.refresh.insertOne(doc("_id" to sha256Hex(refresh), "uid" to uid, "d" to did, "used" to false, "at" to now.asDate(), "exp" to (now + Tokens.REFRESH_TTL_MS).asDate()))
        return AuthResponse(access, refresh, exp, UserDto(uid, username, joinedAt, role == "admin"), did, now)
    }

    suspend fun refresh(token: String): TokenPair {
        val now = ServerClock.now()
        val rec = db.refresh.one(Document("_id", sha256Hex(token))) ?: unauthorized()
        val uid = rec.str("uid")!!
        val did = rec.int("d")!!
        if (rec["used"] == true) {
            // Reuse of a rotated token: assume theft, revoke this device entirely.
            revokeDevice(uid, did, "session_reused")
            unauthorized()
        }
        if ((rec.millis("exp") ?: 0) < now) unauthorized()
        val user = db.users.one(Document("_id", uid)) ?: unauthorized()
        if (user.str("st") == "suspended") forbidden("suspended", "This account is suspended")
        db.devices.one(Document("uid", uid).append("d", did)) ?: unauthorized()
        db.refresh.updateOne(Document("_id", rec.str("_id")), Updates.set("used", true))
        val (access, exp) = tokens.access(uid, did, user.str("role") ?: "member", now)
        val next = Tokens.newRefresh()
        db.refresh.insertOne(doc("_id" to sha256Hex(next), "uid" to uid, "d" to did, "used" to false, "at" to now.asDate(), "exp" to (now + Tokens.REFRESH_TTL_MS).asDate()))
        return TokenPair(access, next, exp, now)
    }

    suspend fun signOut(c: Caller) = revokeDevice(c.uid, c.did, "signed_out")

    suspend fun revokeDevice(uid: String, did: Int, reason: String) {
        val f = Document("uid", uid).append("d", did)
        db.refresh.deleteMany(f)
        db.devices.deleteOne(f)
        db.preKeys.deleteMany(f)
        db.envelopes.deleteMany(Document("toUid", uid).append("toD", did))
        hub.kickUser(uid, AccountEnded(reason), did)
    }

    suspend fun revokeAll(uid: String, reason: String) {
        val f = Document("uid", uid)
        db.refresh.deleteMany(f)
        db.devices.deleteMany(f)
        db.preKeys.deleteMany(f)
        db.envelopes.deleteMany(Document("toUid", uid))
        hub.kickUser(uid, AccountEnded(reason))
    }

    suspend fun changePassword(c: Caller, current: String, next: String, username: String = "") {
        val user = db.users.one(Document("_id", c.uid)) ?: unauthorized()
        if (username.isNotBlank() && username.trim().lowercase() != user.str("u")) bad("username", "Username doesn't match this account")
        val ok = withContext(Dispatchers.Default) { Passwords.verify(current, user.str("ph")) }
        if (!ok) bad("password", "Current password is wrong")
        Limits.passwordProblem(next)?.let { bad("password", it) }
        if (CommonPasswords.contains(next)) bad("password", "That password is too common")
        val hash = withContext(Dispatchers.Default) { Passwords.hash(next) }
        db.users.updateOne(Document("_id", c.uid), Updates.set("ph", hash))
        // Other sessions must sign in again.
        db.refresh.deleteMany(Filters.and(Filters.eq("uid", c.uid), Filters.ne("d", c.did)))
    }

    /** Signed-out password change: username + current password. Ends every session of the account. */
    suspend fun resetPassword(username: String, current: String, next: String, ip: String) {
        val u = username.trim().lowercase()
        val key = "$u|$ip"
        if (!signInLimiter.allow(key)) tooMany()
        val user = db.users.one(Document("u", u))
        val ok = withContext(Dispatchers.Default) { Passwords.verify(current, user?.str("ph")) }
        if (user == null || !ok) unauthorized("Wrong username or password")
        if (user.str("st") == "suspended") forbidden("suspended", "This account is suspended")
        Limits.passwordProblem(next)?.let { bad("password", it) }
        if (CommonPasswords.contains(next)) bad("password", "That password is too common")
        if (next == current) bad("password", "New password must be different")
        signInLimiter.reset(key)
        val hash = withContext(Dispatchers.Default) { Passwords.hash(next) }
        val uid = user.str("_id")!!
        db.users.updateOne(Document("_id", uid), Updates.set("ph", hash))
        db.refresh.deleteMany(Document("uid", uid))
        hub.kickUser(uid, AccountEnded("password_changed"))
        activity.record("password_changed", uid, u)
    }

    suspend fun verifyPassword(c: Caller, password: String) {
        val user = db.users.one(Document("_id", c.uid)) ?: unauthorized()
        val ok = withContext(Dispatchers.Default) { Passwords.verify(password, user.str("ph")) }
        if (!ok) bad("password", "Password is wrong")
    }

    /** Hard-deletes an account and everything tied to it. Used by self-delete and admin delete. */
    suspend fun deleteAccount(uid: String, reason: String): List<String> {
        val user = db.users.one(Document("_id", uid)) ?: notFound()
        if (user.str("role") == "admin") forbidden("admin", "The admin identity can't be deleted")
        revokeAll(uid, reason)
        val hallIds = db.hall.find(Document("uid", uid)).toList().map { it.str("_id")!! }
        media.expireOwnedNow(uid)
        db.hall.deleteMany(Filters.and(Filters.eq("uid", uid), Filters.exists("mid", false)))
        db.envelopes.deleteMany(Document("fromUid", uid))
        db.privateMeta.deleteMany(Filters.or(Filters.eq("fromUid", uid), Filters.eq("toUid", uid)))
        db.conversations.deleteMany(Filters.or(Filters.eq("a", uid), Filters.eq("b", uid)))
        db.users.deleteOne(Document("_id", uid))
        activity.record("account_deleted", uid, user.str("u"))
        return hallIds
    }

    // ---------- devices & keys ----------

    private fun validateRegistration(r: DeviceRegistration) {
        if (r.registrationId !in 1..16380) bad("device", "Bad registration id")
        if (r.preKeys.size > Limits.PREKEY_BATCH || r.kyberPreKeys.size > Limits.PREKEY_BATCH) bad("device", "Too many prekeys")
        listOf(r.identityKey, r.signedPreKey.publicKey, r.signedPreKey.signature, r.kyberLastResort.publicKey).forEach {
            if (it.isBlank() || it.length > 4096) bad("device", "Bad key material")
        }
        r.preKeys.forEach { if (it.publicKey.length > 256) bad("device", "Bad prekey") }
        r.kyberPreKeys.forEach { if (it.publicKey.length > 4096) bad("device", "Bad kyber prekey") }
        if (r.hwid.length > 128) bad("device", "Bad device id")
    }

    private suspend fun registerDevice(uid: String, r: DeviceRegistration): Int {
        // Same phone signing in again (e.g. after uninstall without sign-out): its old device record is dead — drop it,
        // whichever account it belonged to. One app install holds one account at a time.
        if (r.hwid.isNotBlank()) {
            db.devices.find(Document("hw", r.hwid)).toList().forEach { revokeDevice(it.str("uid")!!, it.int("d")!!, "replaced") }
        }
        val existing = db.devices.find(Document("uid", uid)).toList()
        val used = existing.mapNotNull { it.int("d") }.toSet()
        var did = (1..Limits.MAX_DEVICES_PER_USER).firstOrNull { it !in used }
        if (did == null) {
            // Recycle the least recently seen device slot.
            val oldest = existing.minBy { it.millis("seen") ?: 0 }
            did = oldest.int("d")!!
            revokeDevice(uid, did, "replaced")
        }
        val now = ServerClock.now()
        db.devices.insertOne(
            doc(
                "_id" to "$uid:$did", "uid" to uid, "d" to did, "reg" to r.registrationId, "ik" to r.identityKey,
                "spk" to doc("id" to r.signedPreKey.id, "pub" to r.signedPreKey.publicKey, "sig" to r.signedPreKey.signature),
                "kyl" to doc("id" to r.kyberLastResort.id, "pub" to r.kyberLastResort.publicKey, "sig" to r.kyberLastResort.signature),
                "label" to r.label.take(40), "at" to now.asDate(), "seen" to now.asDate(),
                "hw" to r.hwid.ifBlank { null },
            ),
        )
        storePreKeys(uid, did, PreKeyUpload(r.preKeys, r.kyberPreKeys))
        return did
    }

    suspend fun storePreKeys(uid: String, did: Int, up: PreKeyUpload) {
        if (up.preKeys.size > Limits.PREKEY_BATCH || up.kyberPreKeys.size > Limits.PREKEY_BATCH) bad("device", "Too many prekeys")
        val docs = up.preKeys.map { doc("uid" to uid, "d" to did, "k" to "ec", "id" to it.id, "pub" to it.publicKey) } +
            up.kyberPreKeys.map { doc("uid" to uid, "d" to did, "k" to "ky", "id" to it.id, "pub" to it.publicKey, "sig" to it.signature) }
        if (docs.isNotEmpty()) runCatching { db.preKeys.insertMany(docs, com.mongodb.client.model.InsertManyOptions().ordered(false)) }
    }

    suspend fun preKeyCounts(c: Caller): PreKeyCounts {
        val f = Document("uid", c.uid).append("d", c.did)
        return PreKeyCounts(
            ec = db.preKeys.countDocuments(Document(f).append("k", "ec")).toInt(),
            kyber = db.preKeys.countDocuments(Document(f).append("k", "ky")).toInt(),
        )
    }

    suspend fun bundles(userId: String): KeysResponse {
        val user = db.users.one(Document("_id", userId)) ?: notFound("Unknown user")
        if (user.str("st") == "suspended") forbidden("suspended", "This account is suspended")
        val devices = db.devices.find(Document("uid", userId)).toList()
        val out = devices.map { d ->
            val did = d.int("d")!!
            val f = Document("uid", userId).append("d", did)
            val ec = db.preKeys.findOneAndDelete(Document(f).append("k", "ec"))
            val ky = db.preKeys.findOneAndDelete(Document(f).append("k", "ky"))
            val spk = d["spk"] as Document
            val kyl = d["kyl"] as Document
            DeviceBundle(
                deviceId = did,
                registrationId = d.int("reg")!!,
                identityKey = d.str("ik")!!,
                signedPreKey = SignedPreKeyDto(spk.int("id")!!, spk.str("pub")!!, spk.str("sig")!!),
                preKey = ec?.let { PreKeyDto(it.int("id")!!, it.str("pub")!!) },
                kyberPreKey = ky?.let { KyberPreKeyDto(it.int("id")!!, it.str("pub")!!, it.str("sig")!!) }
                    ?: KyberPreKeyDto(kyl.int("id")!!, kyl.str("pub")!!, kyl.str("sig")!!),
            )
        }
        return KeysResponse(userId, out)
    }

    suspend fun deviceIds(userId: String): List<Int> = db.devices.find(Document("uid", userId)).toList().mapNotNull { it.int("d") }.sorted()

    suspend fun touchDevice(c: Caller) {
        val now = ServerClock.now().asDate()
        db.devices.updateOne(Document("uid", c.uid).append("d", c.did), Updates.set("seen", now))
        db.users.updateOne(Document("_id", c.uid), Updates.set("la", now))
    }

    suspend fun setFcm(c: Caller, token: String) {
        db.devices.updateOne(Document("uid", c.uid).append("d", c.did), Updates.set("fcm", token.take(512)))
    }

    // ---------- people ----------

    suspend fun people(query: String?, cursor: String?): UserPage {
        val filters = mutableListOf(Filters.eq("st", "active"))
        query?.trim()?.lowercase()?.filter { it.isLetterOrDigit() || it == '_' || it == '.' }?.takeIf { it.isNotEmpty() }?.let {
            filters += Filters.regex("u", "^" + it.replace(".", "\\."))
        }
        cursor?.let { filters += Filters.gt("u", it) }
        val items = db.users.find(Filters.and(filters)).sort(Sorts.ascending("u")).limit(51).toList()
        val page = items.take(50).map { UserSummary(it.str("_id")!!, it.str("u")!!, it.millis("at") ?: 0) }
        return UserPage(page, if (items.size > 50) page.last().username else null)
    }

    suspend fun profile(id: String): UserSummary {
        val u = db.users.one(Document("_id", id)) ?: notFound()
        return UserSummary(u.str("_id")!!, u.str("u")!!, u.millis("at") ?: 0)
    }

    suspend fun me(c: Caller): UserDto {
        val u = db.users.one(Document("_id", c.uid)) ?: unauthorized()
        return UserDto(c.uid, u.str("u")!!, u.millis("at") ?: 0, u.str("role") == "admin")
    }
}
