package com.rajdeep.vesper.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device-local secrets. A 256-bit random SQLCipher passphrase is wrapped by a non-exportable Android Keystore
 * AES-GCM key (StrongBox when available). The wrapped blob lives in app-private storage and is useless off-device.
 */
object LocalKeys {
    private const val ALIAS = "vesper.db.wrap"
    private const val FILE = "db.key.wrapped"
    private val rng = SecureRandom()

    private fun wrapKey(ctx: Context): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        fun gen(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
        return runCatching { gen(ctx.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")) }.getOrElse { gen(false) }
    }

    /** Returns the SQLCipher passphrase, creating and wrapping it on first use. */
    fun databasePassphrase(ctx: Context): ByteArray {
        val f = File(ctx.noBackupFilesDir, FILE)
        val key = wrapKey(ctx)
        if (f.exists()) {
            val blob = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            return c.doFinal(blob, 12, blob.size - 12)
        }
        val pass = ByteArray(32).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        f.writeBytes(c.iv + c.doFinal(pass))
        return pass
    }
}

/** AES-256-GCM for media blobs. Format: 12-byte nonce || ciphertext || 16-byte tag. One random key per blob. */
object MediaCipher {
    private val rng = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also(rng::nextBytes)

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
