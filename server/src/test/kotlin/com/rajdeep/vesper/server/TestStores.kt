package com.rajdeep.vesper.server

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * InMemorySignalProtocolStore keyed by "name.deviceId" strings. libsignal's own in-memory session map keys by
 * SignalProtocolAddress objects coming back from native code, whose hashing doesn't match — the app's Room store
 * already keys by string, this mirrors it for JVM tests and the sim client.
 */
class StringKeyedStore(id: IdentityKeyPair, reg: Int) : InMemorySignalProtocolStore(id, reg) {
    private val sessions = HashMap<String, ByteArray>()
    private fun k(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? { return sessions[k(address)]?.let { SessionRecord(it) } }
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw org.signal.libsignal.protocol.NoSessionException("no session $it") }
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions[k(address)] = record.serialize() }
    override fun containsSession(address: SignalProtocolAddress) = sessions.containsKey(k(address))
    override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(k(address)) }
    override fun deleteAllSessions(name: String) { sessions.keys.removeIf { it.startsWith("$name.") } }
    override fun getSubDeviceSessions(name: String): List<Int> = sessions.keys.filter { it.startsWith("$name.") }.map { it.substringAfterLast('.').toInt() }
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean { return true }
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange { return super.saveIdentity(address, identityKey) }
}
