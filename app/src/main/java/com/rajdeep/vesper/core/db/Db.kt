package com.rajdeep.vesper.core.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

object MsgState {
    const val PENDING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val FAILED = 3
    const val READ = 4
    const val SYSTEM = 9
}

object Scope {
    const val HALL = "hall"
    const val DIRECT = "direct"
}

/**
 * One row per message in the Hall or a Direct conversation. The whole database is SQLCipher-encrypted.
 * `localId` is the client message id (cmid) for own messages, or "h:<serverId>" / "d:<mid>" for received ones.
 */
@Entity(
    tableName = "messages",
    indices = [Index("scope", "peerId", "createdAt"), Index("serverId"), Index("expiresAt"), Index("state")],
)
data class MessageEntity(
    @PrimaryKey val localId: String,
    val serverId: String? = null,
    val scope: String,
    val peerId: String? = null,
    val senderId: String,
    val senderName: String,
    val kind: String,
    val text: String? = null,
    val mediaJson: String? = null,
    val state: Int,
    val outgoing: Boolean,
    val createdAt: Long,
    val acceptedAt: Long? = null,
    val expiresAt: Long? = null,
    val seq: Long? = null,
    val read: Boolean = true,
    val error: String? = null,
    /** Encrypted blob staged for upload (pending media) — app-private, ciphertext only. */
    val stagedBlob: String? = null,
    val replyJson: String? = null,
    /** Pinned until (local clock-adjusted server ms); null = not pinned. While pinned, [expiresAt] = max(base, pin). */
    val pinnedUntil: Long? = null,
    val pinnedBy: String? = null,
    val pinnedAt: Long? = null,
    /** The message's own expiry while a pin extends [expiresAt]. */
    val baseExpiresAt: Long? = null,
)

@Entity(tableName = "users")
data class UserEntity(@PrimaryKey val id: String, val username: String, val joinedAt: Long)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val value: String)

// ---- libsignal state ----
@Entity(tableName = "sig_identity")
data class IdentityEntity(@PrimaryKey val address: String, val key: ByteArray, val firstSeen: Long)

@Entity(tableName = "sig_prekey")
data class PreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray)

@Entity(tableName = "sig_signed_prekey")
data class SignedPreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray)

@Entity(tableName = "sig_kyber_prekey")
data class KyberPreKeyEntity(@PrimaryKey val id: Int, val record: ByteArray, val lastResort: Boolean)

@Entity(tableName = "sig_kyber_used")
data class KyberUsedEntity(@PrimaryKey val tag: String)

@Entity(tableName = "sig_session")
data class SessionEntity(@PrimaryKey val address: String, val name: String, val deviceId: Int, val record: ByteArray)

data class ConversationRow(
    val peerId: String,
    val peerName: String?,
    val lastKind: String,
    val lastText: String?,
    val lastAt: Long,
    val lastOutgoing: Boolean,
    val lastState: Int,
    val unread: Int,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE scope = 'hall' ORDER BY COALESCE(seq, 9223372036854775807) ASC, createdAt ASC")
    fun hall(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE scope = 'direct' AND peerId = :peer ORDER BY createdAt ASC")
    fun direct(peer: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.peerId AS peerId, u.username AS peerName, m.kind AS lastKind, m.text AS lastText, m.createdAt AS lastAt,
               m.outgoing AS lastOutgoing, m.state AS lastState,
               (SELECT COUNT(*) FROM messages x WHERE x.scope='direct' AND x.peerId = m.peerId AND x.read = 0) AS unread
        FROM messages m LEFT JOIN users u ON u.id = m.peerId
        WHERE m.scope = 'direct' AND m.state != 9 AND m.createdAt = (SELECT MAX(y.createdAt) FROM messages y WHERE y.scope='direct' AND y.peerId = m.peerId AND y.state != 9)
        GROUP BY m.peerId ORDER BY m.createdAt DESC
        """,
    )
    fun conversations(): Flow<List<ConversationRow>>

    @Query("SELECT COUNT(*) FROM messages WHERE scope='direct' AND read = 0")
    fun unreadDirect(): Flow<Int>

    @Query("SELECT * FROM messages WHERE localId = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE serverId = :sid LIMIT 1")
    suspend fun byServerId(sid: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(m: MessageEntity): Long

    @Upsert
    suspend fun upsert(m: MessageEntity)

    @Query("SELECT * FROM messages WHERE state = 0 ORDER BY createdAt ASC")
    suspend fun pending(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE state = 0")
    fun pendingCount(): Flow<Int>

    @Query("UPDATE messages SET state = :state, serverId = :sid, acceptedAt = :at, expiresAt = :exp, seq = COALESCE(:seq, seq), error = NULL, stagedBlob = NULL WHERE localId = :id")
    suspend fun markSent(id: String, sid: String, at: Long, exp: Long, seq: Long?, state: Int = MsgState.SENT)

    @Query("UPDATE messages SET state = 3, error = :err WHERE localId = :id")
    suspend fun markFailed(id: String, err: String)

    @Query("UPDATE messages SET state = 0, error = NULL WHERE localId = :id")
    suspend fun retry(id: String)

    @Query("UPDATE messages SET state = 2 WHERE serverId = :sid AND state = 1 AND outgoing = 1")
    suspend fun markDelivered(sid: String)

    @Query("UPDATE messages SET mediaJson = :json WHERE localId = :id")
    suspend fun setMedia(id: String, json: String)

    @Query("UPDATE messages SET read = 1 WHERE scope='direct' AND peerId = :peer AND read = 0")
    suspend fun markRead(peer: String)

    @Query("SELECT serverId FROM messages WHERE scope='direct' AND peerId = :peer AND read = 0 AND outgoing = 0 AND serverId IS NOT NULL")
    suspend fun unreadServerIds(peer: String): List<String>

    @Query("SELECT serverId FROM messages WHERE scope='direct' AND outgoing = 1 AND state IN (1, 2) AND serverId IS NOT NULL ORDER BY createdAt DESC LIMIT 500")
    suspend fun unseenOutgoing(): List<String>

    @Query("UPDATE messages SET state = 2 WHERE serverId IN (:sids) AND outgoing = 1 AND state = 1")
    suspend fun markDeliveredMany(sids: List<String>)

    @Query("UPDATE messages SET state = 4 WHERE serverId IN (:sids) AND outgoing = 1 AND state IN (1, 2)")
    suspend fun markSeen(sids: List<String>)

    @Query("SELECT * FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun expired(now: Long): List<MessageEntity>

    @Query("SELECT MIN(expiresAt) FROM messages WHERE expiresAt IS NOT NULL")
    suspend fun nextExpiry(): Long?

    @Query("DELETE FROM messages WHERE localId IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM messages WHERE scope='hall' AND serverId IN (:sids)")
    suspend fun deleteHallByServerIds(sids: List<String>)

    @Query("SELECT * FROM messages WHERE serverId IN (:sids)")
    suspend fun byServerIds(sids: List<String>): List<MessageEntity>

    @Query("DELETE FROM messages WHERE scope='direct' AND serverId IN (:sids)")
    suspend fun deleteDirectByServerIds(sids: List<String>)

    @Query("SELECT * FROM messages WHERE scope='hall' AND serverId IN (:sids)")
    suspend fun hallByServerIds(sids: List<String>): List<MessageEntity>

    @Query("SELECT MAX(seq) FROM messages WHERE scope='hall'")
    suspend fun maxHallSeq(): Long?

    @Query("SELECT * FROM messages WHERE scope = 'hall' AND pinnedUntil IS NOT NULL ORDER BY pinnedAt DESC")
    fun hallPins(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE scope = 'direct' AND peerId = :peer AND pinnedUntil IS NOT NULL ORDER BY pinnedAt DESC")
    fun directPins(peer: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE scope = :scope AND COALESCE(peerId, '') = :peer AND pinnedUntil > :now ORDER BY pinnedAt ASC")
    suspend fun activePins(scope: String, peer: String, now: Long): List<MessageEntity>

    @Query(
        """UPDATE messages SET pinnedUntil = :until, pinnedBy = :by, pinnedAt = :at,
           baseExpiresAt = COALESCE(baseExpiresAt, expiresAt),
           expiresAt = CASE WHEN COALESCE(baseExpiresAt, expiresAt) IS NULL THEN NULL ELSE MAX(COALESCE(baseExpiresAt, expiresAt), :until) END
           WHERE serverId = :sid""",
    )
    suspend fun pin(sid: String, until: Long, by: String, at: Long)

    @Query("UPDATE messages SET pinnedUntil = NULL, pinnedBy = NULL, pinnedAt = NULL, expiresAt = COALESCE(baseExpiresAt, expiresAt), baseExpiresAt = NULL WHERE serverId = :sid")
    suspend fun unpin(sid: String)

    @Query("SELECT serverId FROM messages WHERE scope = :scope AND pinnedUntil IS NOT NULL AND serverId IS NOT NULL AND serverId NOT LIKE 'self:%'")
    suspend fun pinnedServerIds(scope: String): List<String>

    @Query("SELECT mediaJson FROM messages WHERE mediaJson IS NOT NULL")
    suspend fun allMediaJson(): List<String>
}

@Dao
interface UserDao {
    @Upsert suspend fun upsert(users: List<UserEntity>)
    @Query("SELECT * FROM users WHERE id = :id") suspend fun get(id: String): UserEntity?
    @Query("SELECT * FROM users WHERE id = :id") fun observe(id: String): Flow<UserEntity?>
    @Query("SELECT * FROM users") fun all(): Flow<List<UserEntity>>
}

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :k") suspend fun get(k: String): String?
    @Query("SELECT value FROM kv WHERE `key` = :k") fun observe(k: String): Flow<String?>
    @Upsert suspend fun put(e: KvEntity)
    @Query("DELETE FROM kv WHERE `key` = :k") suspend fun remove(k: String)
    /** People this user has talked to privately (key contact:<id>, value = last activity ms). Survives message expiry. */
    @Query("SELECT * FROM kv WHERE `key` LIKE 'contact:%'") fun contacts(): Flow<List<KvEntity>>
    /** Read receipts waiting for a connection (key rr:<peer>, value = comma-separated message ids). */
    @Query("SELECT * FROM kv WHERE `key` LIKE 'rr:%'") suspend fun pendingReads(): List<KvEntity>
    /** "Delete for me" Hall tombstones (key hid:<id>, value = expiry ms) that can be dropped once the message is gone. */
    @Query("DELETE FROM kv WHERE `key` LIKE 'hid:%' AND CAST(value AS INTEGER) > 1 AND CAST(value AS INTEGER) < :now") suspend fun purgeHallTombstones(now: Long)
}

@Dao
interface SignalDao {
    @Query("SELECT * FROM sig_identity WHERE address = :a") fun identity(a: String): IdentityEntity?
    @Upsert fun putIdentity(e: IdentityEntity)
    @Query("SELECT * FROM sig_identity WHERE address LIKE :prefix || '.%'") fun identitiesFor(prefix: String): List<IdentityEntity>

    @Query("SELECT * FROM sig_prekey WHERE id = :id") fun preKey(id: Int): PreKeyEntity?
    @Upsert fun putPreKey(e: PreKeyEntity)
    @Query("DELETE FROM sig_prekey WHERE id = :id") fun removePreKey(id: Int)
    @Query("SELECT MAX(id) FROM sig_prekey") fun maxPreKeyId(): Int?

    @Query("SELECT * FROM sig_signed_prekey WHERE id = :id") fun signed(id: Int): SignedPreKeyEntity?
    @Query("SELECT * FROM sig_signed_prekey") fun allSigned(): List<SignedPreKeyEntity>
    @Upsert fun putSigned(e: SignedPreKeyEntity)
    @Query("DELETE FROM sig_signed_prekey WHERE id = :id") fun removeSigned(id: Int)

    @Query("SELECT * FROM sig_kyber_prekey WHERE id = :id") fun kyber(id: Int): KyberPreKeyEntity?
    @Query("SELECT * FROM sig_kyber_prekey") fun allKyber(): List<KyberPreKeyEntity>
    @Upsert fun putKyber(e: KyberPreKeyEntity)
    @Query("DELETE FROM sig_kyber_prekey WHERE id = :id") fun removeKyber(id: Int)
    @Query("SELECT MAX(id) FROM sig_kyber_prekey") fun maxKyberId(): Int?
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertKyberUsed(e: KyberUsedEntity)

    @Query("SELECT * FROM sig_session WHERE address = :a") fun session(a: String): SessionEntity?
    @Query("SELECT * FROM sig_session WHERE name = :name") fun sessionsFor(name: String): List<SessionEntity>
    @Upsert fun putSession(e: SessionEntity)
    @Query("DELETE FROM sig_session WHERE address = :a") fun deleteSession(a: String)
    @Query("DELETE FROM sig_session WHERE name = :name") fun deleteSessionsFor(name: String)
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN replyJson TEXT DEFAULT NULL")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN pinnedUntil INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN pinnedBy TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN pinnedAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN baseExpiresAt INTEGER DEFAULT NULL")
    }
}

@Database(
    entities = [
        MessageEntity::class, UserEntity::class, KvEntity::class, IdentityEntity::class, PreKeyEntity::class,
        SignedPreKeyEntity::class, KyberPreKeyEntity::class, KyberUsedEntity::class, SessionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class VesperDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun users(): UserDao
    abstract fun kv(): KvDao
    abstract fun signal(): SignalDao
}
