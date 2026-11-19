package com.rajdeep.vesper.shared

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/** Product limits shared by app and server. Message caps are config-like constants, never user limits. */
object Limits {
    const val TEXT_TTL_MS: Long = 72L * 60 * 60 * 1000
    const val MEDIA_TTL_MS: Long = 24L * 60 * 60 * 1000
    const val MAX_TEXT_CHARS = 4000
    const val MAX_BODY_BYTES = 16 * 1024
    const val MAX_MEDIA_BYTES = 2L * 1024 * 1024
    const val MAX_VOICE_MS = 5L * 60 * 1000
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 20
    const val PASSWORD_MIN = 10
    const val PASSWORD_MAX = 128
    const val PREKEY_BATCH = 100
    const val PREKEY_LOW_WATER = 20
    const val MAX_DEVICES_PER_USER = 127
    val USERNAME_REGEX = Regex("^[a-z0-9_.]{3,20}$")
    val RESERVED = setOf("admin", "administrator", "system", "vesper", "support", "root", "moderator", "staff", "hall", "console", "null", "undefined")

    fun ttlFor(kind: MessageKind): Long = if (kind == MessageKind.TEXT) TEXT_TTL_MS else MEDIA_TTL_MS

    /** Returns null when valid, else a short user-facing reason. */
    fun usernameProblem(raw: String): String? {
        val u = raw.trim().lowercase()
        return when {
            u.length < USERNAME_MIN -> "At least $USERNAME_MIN characters"
            u.length > USERNAME_MAX -> "At most $USERNAME_MAX characters"
            !USERNAME_REGEX.matches(u) -> "Use a–z, 0–9, _ or ."
            u in RESERVED -> "That name is reserved"
            else -> null
        }
    }

    fun passwordProblem(p: String): String? = when {
        p.length < PASSWORD_MIN -> "At least $PASSWORD_MIN characters"
        p.length > PASSWORD_MAX -> "At most $PASSWORD_MAX characters"
        p.isBlank() -> "Password can't be blank"
        else -> null
    }
}

val VesperJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "t"
}

@Serializable
enum class MessageKind { @SerialName("text") TEXT, @SerialName("voice") VOICE, @SerialName("image") IMAGE }

@Serializable
enum class MediaScope { @SerialName("hall") HALL, @SerialName("private") PRIVATE }

// ---------- auth ----------

@Serializable data class SignedPreKeyDto(val id: Int, val publicKey: String, val signature: String)
@Serializable data class PreKeyDto(val id: Int, val publicKey: String)
@Serializable data class KyberPreKeyDto(val id: Int, val publicKey: String, val signature: String)

@Serializable
data class DeviceRegistration(
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberLastResort: KyberPreKeyDto,
    val preKeys: List<PreKeyDto>,
    val kyberPreKeys: List<KyberPreKeyDto>,
    val label: String = "Android",
)

@Serializable data class AuthRequest(val username: String, val password: String, val device: DeviceRegistration)
@Serializable data class UserDto(val id: String, val username: String, val joinedAt: Long, val isAdmin: Boolean = false)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val user: UserDto,
    val deviceId: Int,
    val serverTime: Long,
)

@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class TokenPair(val accessToken: String, val refreshToken: String, val accessExpiresAt: Long, val serverTime: Long)
@Serializable data class Availability(val available: Boolean, val reason: String? = null)
@Serializable data class PasswordChange(val current: String, val next: String, val username: String = "")
@Serializable data class PasswordReset(val username: String, val current: String, val next: String)
@Serializable data class PasswordConfirm(val password: String)
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class Ok(val ok: Boolean = true)
@Serializable data class FcmToken(val token: String)

// ---------- people ----------

@Serializable data class UserSummary(val id: String, val username: String, val joinedAt: Long)
@Serializable data class UserPage(val items: List<UserSummary>, val nextCursor: String? = null)

// ---------- keys ----------

@Serializable
data class DeviceBundle(
    val deviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val preKey: PreKeyDto? = null,
    val kyberPreKey: KyberPreKeyDto,
)

@Serializable data class KeysResponse(val userId: String, val devices: List<DeviceBundle>)
@Serializable data class PreKeyCounts(val ec: Int, val kyber: Int)
@Serializable data class PreKeyUpload(val preKeys: List<PreKeyDto> = emptyList(), val kyberPreKeys: List<KyberPreKeyDto> = emptyList())
@Serializable data class DeviceList(val userId: String, val deviceIds: List<Int>)

// ---------- message bodies (Hall: TLS + at-rest; Direct: inside libsignal ciphertext) ----------

@Serializable
data class MediaRef(
    val mediaId: String,
    val key: String,
    val mime: String,
    val bytes: Long,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val waveform: List<Int>? = null,
    val blurhash: String? = null,
)

@Serializable data class MessageBody(val text: String? = null, val media: MediaRef? = null)

/** Plaintext inside a Direct envelope. */
@Serializable
data class DirectPayload(
    val cmid: String,
    val kind: MessageKind,
    val body: MessageBody,
    val fromUserId: String,
    val toUserId: String,
    val sentAtClient: Long,
)

@Serializable
data class HallMessageDto(
    val id: String,
    val seq: Long,
    val senderId: String,
    val senderName: String,
    val kind: MessageKind,
    val body: MessageBody,
    val acceptedAt: Long,
    val expiresAt: Long,
    val cmid: String? = null,
)

@Serializable data class HallPage(val items: List<HallMessageDto>, val serverTime: Long)

@Serializable data class OutEnvelope(val toUserId: String, val deviceId: Int, val type: Int, val body: String)

@Serializable
data class DirectEnvelopeDto(
    val id: String,
    val mid: String,
    val fromUserId: String,
    val fromDeviceId: Int,
    val toUserId: String,
    val type: Int,
    val body: String,
    val kind: MessageKind,
    val acceptedAt: Long,
    val expiresAt: Long,
)

@Serializable data class PendingEnvelopes(val items: List<DirectEnvelopeDto>, val serverTime: Long)
@Serializable data class AckIds(val ids: List<String>)

@Serializable
data class DeviceMismatch(
    val missing: Map<String, List<Int>> = emptyMap(),
    val extra: Map<String, List<Int>> = emptyMap(),
    val stale: Map<String, List<Int>> = emptyMap(),
)

// ---------- media ----------

@Serializable
data class MediaIntentRequest(val kind: MessageKind, val scope: MediaScope, val bytes: Long, val sha256: String, val peerUserId: String? = null)

@Serializable
data class UploadTicket(
    val method: String,
    val url: String,
    val fields: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val fileField: String? = null,
    val expiresAt: Long,
)

@Serializable data class MediaIntentResponse(val mediaId: String, val ticket: UploadTicket)

/** Provider response echoed back to the server at commit (Cloudinary: public_id/version/signature/bytes). */
@Serializable data class MediaCommit(val providerResponse: Map<String, String> = emptyMap())
@Serializable data class MediaUrl(val url: String, val expiresAt: Long)

// ---------- admin ----------

@Serializable
data class AdminOverview(
    val registered: Long,
    val onlineNow: Int,
    val active24h: Long,
    val hall24h: Long,
    val hall72h: Long,
    val directText: Long,
    val directVoice: Long,
    val directImage: Long,
    val mediaResidentBytes: Long,
    val dbSizeBytes: Long,
    val dbCapBytes: Long,
    val mediaProvider: String,
    val mediaCreditsUsed: Double? = null,
    val mediaCreditsLimit: Double? = null,
    val lastKeepAlivePing: Long? = null,
    val activity72h: List<Int> = emptyList(),
    val serverStartedAt: Long,
    val serverTime: Long,
)

@Serializable
data class AdminMember(
    val id: String,
    val username: String,
    val joinedAt: Long,
    val lastActiveAt: Long?,
    val online: Boolean,
    val suspended: Boolean,
    val hallMuteUntil: Long?,
    val devices: Int,
    val isAdmin: Boolean,
)

@Serializable data class PartnerMeta(val userId: String, val username: String, val startedAt: Long, val lastActivityAt: Long, val text: Int, val voice: Int, val image: Int)
@Serializable data class AdminMemberDetail(val member: AdminMember, val hallMessages72h: Long, val partners: List<PartnerMeta>)

@Serializable
data class ActivityEvent(
    val id: String,
    val type: String,
    val at: Long,
    val userId: String? = null,
    val username: String? = null,
    val peerId: String? = null,
    val peerName: String? = null,
    val kind: MessageKind? = null,
)

@Serializable data class ActivityPage(val items: List<ActivityEvent>)

@Serializable
data class ConversationMeta(
    val a: String, val aName: String, val b: String, val bName: String,
    val startedAt: Long, val lastActivityAt: Long, val text: Int, val voice: Int, val image: Int,
)

@Serializable data class ConversationPage(val items: List<ConversationMeta>)
@Serializable data class MemberPage(val items: List<AdminMember>)
@Serializable data class MuteRequest(val minutes: Long? = null)
@Serializable data class AdminDevice(val deviceId: Int, val label: String, val createdAt: Long, val lastSeenAt: Long, val current: Boolean)
@Serializable data class AdminDeviceList(val items: List<AdminDevice>)

// ---------- realtime frames ----------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
sealed interface Frame

@Serializable @SerialName("hall.send") data class HallSend(val cmid: String, val kind: MessageKind, val body: MessageBody) : Frame
@Serializable @SerialName("direct.send") data class DirectSend(val cmid: String, val toUserId: String, val kind: MessageKind, val mediaId: String? = null, val envelopes: List<OutEnvelope>) : Frame
@Serializable @SerialName("direct.ack") data class DirectAck(val ids: List<String>) : Frame
@Serializable @SerialName("ping") data class Ping(val at: Long = 0) : Frame

@Serializable @SerialName("pong") data class Pong(val serverTime: Long) : Frame
@Serializable @SerialName("ack") data class Ack(val cmid: String, val id: String, val acceptedAt: Long, val expiresAt: Long, val seq: Long? = null) : Frame
@Serializable @SerialName("nack") data class Nack(val cmid: String, val code: String, val message: String? = null, val mismatch: DeviceMismatch? = null) : Frame
@Serializable @SerialName("hall.msg") data class HallMsg(val msg: HallMessageDto) : Frame
@Serializable @SerialName("hall.deleted") data class HallDeleted(val ids: List<String>) : Frame
@Serializable @SerialName("direct.env") data class DirectEnv(val env: DirectEnvelopeDto) : Frame
@Serializable @SerialName("direct.delivered") data class DirectDelivered(val mid: String, val toUserId: String) : Frame
@Serializable @SerialName("account.ended") data class AccountEnded(val reason: String) : Frame
@Serializable @SerialName("hall.muted") data class HallMuted(val until: Long?) : Frame
@Serializable @SerialName("admin.activity") data class AdminActivity(val event: ActivityEvent) : Frame
@Serializable @SerialName("admin.presence") data class AdminPresence(val userId: String, val online: Boolean, val lastActiveAt: Long) : Frame

object Nacks {
    const val MUTED = "muted"
    const val INVALID = "invalid"
    const val DEVICES = "devices"
    const val MEDIA = "media"
    const val RATE = "rate"
    const val UNKNOWN_USER = "unknown_user"
}
