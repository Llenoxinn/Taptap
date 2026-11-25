package io.github.llenoxinn.taptap.android.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.llenoxinn.taptap.mesh.Crypto
import io.github.llenoxinn.taptap.mesh.SignKeypair
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SeedStore {
    fun loadOrCreate(): ByteArray
}

/**
 * Generates a 32-byte Ed25519 seed once per install, wraps it with an Android Keystore AES-256-GCM
 * key, and writes the wrapped blob to `noBackupFilesDir`.
 */
class KeystoreSeedStore(private val context: Context) : SeedStore {
    @Synchronized
    override fun loadOrCreate(): ByteArray {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (file.exists()) {
            val raw = file.readBytes()
            if (raw.size > 2) {
                val ivLen = raw[0].toInt() and 0xFF
                if (raw.size > 1 + ivLen + GCM_TAG_BYTES) {
                    val iv = raw.copyOfRange(1, 1 + ivLen)
                    val cipherText = raw.copyOfRange(1 + ivLen, raw.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                    return cipher.doFinal(cipherText)
                }
            }
        }
        val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(seed)
        val blob = ByteArray(1 + iv.size + cipherText.size)
        blob[0] = iv.size.toByte()
        iv.copyInto(blob, 1)
        cipherText.copyInto(blob, 1 + iv.size)

        val tmp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) {
            file.writeBytes(blob)
            tmp.delete()
        }
        return seed
    }

    private fun getOrCreateAesKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "taptap_device_seed_wrap_v1"
        const val FILE_NAME = "device_seed.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
        const val SEED_BYTES = 32
    }
}

class DeviceIdentity(crypto: Crypto, seedStore: SeedStore) {
    val keypair: SignKeypair by lazy { crypto.signKeypair(seedStore.loadOrCreate()) }

    companion object {
        fun fromSeed(crypto: Crypto, seed: ByteArray): DeviceIdentity =
            DeviceIdentity(crypto, object : SeedStore {
                override fun loadOrCreate(): ByteArray = seed.copyOf()
            })
    }
}
