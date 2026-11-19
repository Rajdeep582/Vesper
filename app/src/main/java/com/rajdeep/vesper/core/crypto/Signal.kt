package com.rajdeep.vesper.core.crypto

import android.util.Base64
import com.rajdeep.vesper.core.db.IdentityEntity
import com.rajdeep.vesper.core.db.KyberPreKeyEntity
import com.rajdeep.vesper.core.db.KyberUsedEntity
import com.rajdeep.vesper.core.db.PreKeyEntity
import com.rajdeep.vesper.core.db.SessionEntity
import com.rajdeep.vesper.core.db.SignalDao
import com.rajdeep.vesper.core.db.SignedPreKeyEntity
import com.rajdeep.vesper.shared.DeviceBundle
import com.rajdeep.vesper.shared.DeviceRegistration
import com.rajdeep.vesper.shared.KyberPreKeyDto
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.shared.PreKeyDto
import com.rajdeep.vesper.shared.PreKeyUpload
import com.rajdeep.vesper.shared.SignedPreKeyDto
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.security.SecureRandom

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

/** Identity material for this device. Private keys never leave the SQLCipher database. */
class LocalIdentity(val keyPair: IdentityKeyPair, val registrationId: Int)

/** libsignal stores backed by the encrypted Room database. All calls happen off the main thread. */
class RoomSignalStores(private val dao: SignalDao, private val identity: () -> LocalIdentity) :
    IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {

    /** Called when a peer device's identity key replaced a previously seen one. */
    var onIdentityChanged: (SignalProtocolAddress) -> Unit = {}

    private fun key(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"

    // identity
    override fun getIdentityKeyPair(): IdentityKeyPair = identity().keyPair
    override fun getLocalRegistrationId(): Int = identity().registrationId
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = dao.identity(key(address))
        if (existing != null && existing.key.contentEquals(identityKey.serialize())) return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        dao.putIdentity(IdentityEntity(key(address), identityKey.serialize(), System.currentTimeMillis()))
        return if (existing == null) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            onIdentityChanged(address)
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }
    // Trust on first use; replacements are surfaced to the user as a security notice instead of blocking.
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction) = true
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = dao.identity(key(address))?.let { IdentityKey(it.key) }

    // one-time EC prekeys
    override fun loadPreKey(preKeyId: Int): PreKeyRecord = dao.preKey(preKeyId)?.let { PreKeyRecord(it.record) } ?: throw InvalidKeyIdException("no prekey $preKeyId")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = dao.putPreKey(PreKeyEntity(preKeyId, record.serialize()))
    override fun containsPreKey(preKeyId: Int) = dao.preKey(preKeyId) != null
    override fun removePreKey(preKeyId: Int) = dao.removePreKey(preKeyId)

    // signed prekeys
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        dao.signed(signedPreKeyId)?.let { SignedPreKeyRecord(it.record) } ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = dao.allSigned().map { SignedPreKeyRecord(it.record) }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = dao.putSigned(SignedPreKeyEntity(signedPreKeyId, record.serialize()))
    override fun containsSignedPreKey(signedPreKeyId: Int) = dao.signed(signedPreKeyId) != null
    override fun removeSignedPreKey(signedPreKeyId: Int) = dao.removeSigned(signedPreKeyId)

    // kyber prekeys
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        dao.kyber(kyberPreKeyId)?.let { KyberPreKeyRecord(it.record) } ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = dao.allKyber().map { KyberPreKeyRecord(it.record) }
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = dao.putKyber(KyberPreKeyEntity(kyberPreKeyId, record.serialize(), lastResort = false))
    override fun containsKyberPreKey(kyberPreKeyId: Int) = dao.kyber(kyberPreKeyId) != null
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val rec = dao.kyber(kyberPreKeyId) ?: return
        if (!rec.lastResort) {
            dao.removeKyber(kyberPreKeyId)
        } else {
            // Last-resort keys may be reused, but never with the same base key.
            val tag = "$kyberPreKeyId:$signedPreKeyId:${baseKey.serialize().b64()}"
            try { dao.insertKyberUsed(KyberUsedEntity(tag)) } catch (e: Exception) { throw ReusedBaseKeyException() }
        }
    }

    // sessions
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? = dao.session(key(address))?.let { SessionRecord(it.record) }
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw org.signal.libsignal.protocol.NoSessionException("no session for $it") }
    override fun getSubDeviceSessions(name: String): List<Int> = dao.sessionsFor(name).map { it.deviceId }
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        dao.putSession(SessionEntity(key(address), address.name, address.deviceId, record.serialize()))
    override fun containsSession(address: SignalProtocolAddress) = dao.session(key(address)) != null
    override fun deleteSession(address: SignalProtocolAddress) = dao.deleteSession(key(address))
    override fun deleteAllSessions(name: String) = dao.deleteSessionsFor(name)

    fun storeLastResortKyber(record: KyberPreKeyRecord) = dao.putKyber(KyberPreKeyEntity(record.id, record.serialize(), lastResort = true))
}

/** Key generation for a fresh device registration (every sign-in is a new device). */
object SignalKeygen {
    private val rng = SecureRandom()

    class Generated(val identity: LocalIdentity, val registration: DeviceRegistration, val apply: (RoomSignalStores) -> Unit)

    fun newDevice(label: String): Generated {
        val identity = IdentityKeyPair.generate()
        val regId = 1 + rng.nextInt(16380)
        val signed = ECKeyPair.generate()
        val signedRec = SignedPreKeyRecord(1, System.currentTimeMillis(), signed, identity.privateKey.calculateSignature(signed.publicKey.serialize()))
        val lastResort = kyber(1, identity)
        val preKeys = (1..Limits.PREKEY_BATCH).map { PreKeyRecord(it, ECKeyPair.generate()) }
        val kybers = (2..Limits.PREKEY_BATCH + 1).map { kyber(it, identity) }
        val reg = DeviceRegistration(
            registrationId = regId,
            identityKey = identity.publicKey.serialize().b64(),
            signedPreKey = SignedPreKeyDto(1, signed.publicKey.serialize().b64(), signedRec.signature.b64()),
            kyberLastResort = KyberPreKeyDto(1, lastResort.keyPair.publicKey.serialize().b64(), lastResort.signature.b64()),
            preKeys = preKeys.map { PreKeyDto(it.id, it.keyPair.publicKey.serialize().b64()) },
            kyberPreKeys = kybers.map { KyberPreKeyDto(it.id, it.keyPair.publicKey.serialize().b64(), it.signature.b64()) },
            label = label,
        )
        return Generated(LocalIdentity(identity, regId), reg) { stores ->
            stores.storeSignedPreKey(1, signedRec)
            stores.storeLastResortKyber(lastResort)
            preKeys.forEach { stores.storePreKey(it.id, it) }
            kybers.forEach { stores.storeKyberPreKey(it.id, it) }
        }
    }

    private fun kyber(id: Int, identity: IdentityKeyPair): KyberPreKeyRecord {
        val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        return KyberPreKeyRecord(id, System.currentTimeMillis(), kp, identity.privateKey.calculateSignature(kp.publicKey.serialize()))
    }

    /** Generates a replenishment batch continuing after the highest ids in use. */
    fun replenish(stores: RoomSignalStores, dao: SignalDao, identity: IdentityKeyPair, needEc: Boolean, needKyber: Boolean): PreKeyUpload {
        val ec = if (needEc) {
            val start = (dao.maxPreKeyId() ?: 0) + 1
            (start until start + Limits.PREKEY_BATCH).map { id -> PreKeyRecord(id, ECKeyPair.generate()).also { stores.storePreKey(id, it) } }
        } else emptyList()
        val ky = if (needKyber) {
            val start = (dao.maxKyberId() ?: 1) + 1
            (start until start + Limits.PREKEY_BATCH).map { id -> kyber(id, identity).also { stores.storeKyberPreKey(id, it) } }
        } else emptyList()
        return PreKeyUpload(
            ec.map { PreKeyDto(it.id, it.keyPair.publicKey.serialize().b64()) },
            ky.map { KyberPreKeyDto(it.id, it.keyPair.publicKey.serialize().b64(), it.signature.b64()) },
        )
    }
}

/** Session-level encrypt/decrypt over the stores. */
class SignalCipher(private val stores: RoomSignalStores, private val local: () -> SignalProtocolAddress) {

    fun hasSession(userId: String, deviceId: Int) = stores.containsSession(SignalProtocolAddress(userId, deviceId))

    fun deleteSession(userId: String, deviceId: Int) = stores.deleteSession(SignalProtocolAddress(userId, deviceId))

    fun knownDevices(userId: String): List<Int> = stores.getSubDeviceSessions(userId)

    fun processBundle(userId: String, b: DeviceBundle) {
        val bundle = PreKeyBundle(
            b.registrationId,
            b.deviceId,
            b.preKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            b.preKey?.let { ECPublicKey(it.publicKey.unb64()) },
            b.signedPreKey.id,
            ECPublicKey(b.signedPreKey.publicKey.unb64()),
            b.signedPreKey.signature.unb64(),
            IdentityKey(b.identityKey.unb64()),
            b.kyberPreKey.id,
            KEMPublicKey(b.kyberPreKey.publicKey.unb64()),
            b.kyberPreKey.signature.unb64(),
        )
        // libsignal 0.108: SessionBuilder takes (remote, local); SessionCipher takes (local, remote).
        SessionBuilder(stores, stores, stores, stores, SignalProtocolAddress(userId, b.deviceId), local()).process(bundle)
    }

    private fun cipher(userId: String, deviceId: Int) =
        SessionCipher(stores, stores, stores, stores, stores, local(), SignalProtocolAddress(userId, deviceId))

    fun encrypt(userId: String, deviceId: Int, plain: ByteArray): Pair<Int, ByteArray> {
        val msg = cipher(userId, deviceId).encrypt(plain)
        return msg.type to msg.serialize()
    }

    fun decrypt(userId: String, deviceId: Int, type: Int, body: ByteArray): ByteArray {
        val c = cipher(userId, deviceId)
        return when (type) {
            CiphertextMessage.PREKEY_TYPE -> c.decrypt(PreKeySignalMessage(body))
            CiphertextMessage.WHISPER_TYPE -> c.decrypt(SignalMessage(body))
            else -> error("unsupported envelope type $type")
        }
    }

    /** 60-digit safety number between this device and a peer device. */
    fun safetyNumber(myUserId: String, peerUserId: String, peerDeviceId: Int): String? {
        val theirs = stores.getIdentity(SignalProtocolAddress(peerUserId, peerDeviceId)) ?: return null
        val mine = stores.identityKeyPair.publicKey
        val fp = NumericFingerprintGenerator(5200).createFor(2, myUserId.toByteArray(), mine, peerUserId.toByteArray(), theirs)
        return fp.displayableFingerprint.displayText
    }
}
