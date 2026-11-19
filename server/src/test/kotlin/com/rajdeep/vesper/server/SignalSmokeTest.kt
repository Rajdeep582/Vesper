package com.rajdeep.vesper.server

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies the libsignal usage pattern the app relies on (PQXDH bundle → session → encrypt/decrypt). */
class SignalSmokeTest {
    @Test
    fun bundleSessionRoundTrip() {
        val bobId = IdentityKeyPair.generate()
        val bob = StringKeyedStore(bobId, 77)
        val spk = ECKeyPair.generate()
        val spkRec = SignedPreKeyRecord(1, System.currentTimeMillis(), spk, bobId.privateKey.calculateSignature(spk.publicKey.serialize()))
        bob.storeSignedPreKey(1, spkRec)
        val pre = PreKeyRecord(5, ECKeyPair.generate()); bob.storePreKey(5, pre)
        val kk = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val ky = KyberPreKeyRecord(9, System.currentTimeMillis(), kk, bobId.privateKey.calculateSignature(kk.publicKey.serialize()))
        bob.storeKyberPreKey(9, ky)

        val alice = StringKeyedStore(IdentityKeyPair.generate(), 55)
        val aliceAddr = SignalProtocolAddress("alice", 1)
        val bobAddr = SignalProtocolAddress("bob", 2)
        val bundle = PreKeyBundle(77, 2, 5, pre.keyPair.publicKey, 1, spk.publicKey, spkRec.signature, bobId.publicKey, 9, kk.publicKey, ky.signature)
        // libsignal 0.108: SessionBuilder takes (remote, local) while SessionCipher takes (local, remote).
        SessionBuilder(alice, bobAddr, aliceAddr).process(bundle)
        println("contains=" + alice.containsSession(bobAddr) + " load=" + (alice.loadSession(bobAddr) != null))
        val msg = SessionCipher(alice, aliceAddr, bobAddr).encrypt("hi".toByteArray())
        val plain = SessionCipher(bob, bobAddr, aliceAddr).decrypt(PreKeySignalMessage(msg.serialize()))
        assertEquals("hi", String(plain))
    }
}
