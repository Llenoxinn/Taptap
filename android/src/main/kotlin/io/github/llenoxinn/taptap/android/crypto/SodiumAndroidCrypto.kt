package io.github.llenoxinn.taptap.android.crypto

import com.goterl.lazysodium.SodiumAndroid
import io.github.llenoxinn.taptap.mesh.BoxKeypair
import io.github.llenoxinn.taptap.mesh.Crypto
import io.github.llenoxinn.taptap.mesh.SignKeypair

/** [Crypto] backed by libsodium via lazysodium-android. */
class SodiumAndroidCrypto : Crypto {
    private val sodium: SodiumAndroid get() = shared

    override fun signKeypair(seed: ByteArray?): SignKeypair {
        val pk = ByteArray(32)
        val sk = ByteArray(64)
        val rc = if (seed == null) {
            sodium.crypto_sign_keypair(pk, sk)
        } else {
            sodium.crypto_sign_seed_keypair(pk, sk, requireSeed(seed))
        }
        check(rc == 0) { "crypto_sign_keypair failed" }
        return SignKeypair(pk, sk)
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        require(secretKey.size == 64) { "sign secret key must be 64 bytes" }
        val sig = ByteArray(64)
        check(sodium.crypto_sign_detached(sig, null, message, message.size.toLong(), secretKey) == 0) { "sign failed" }
        return sig
    }

    override fun verify(sig: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        sig.size == 64 && publicKey.size == 32 &&
            sodium.crypto_sign_verify_detached(sig, message, message.size.toLong(), publicKey) == 0

    override fun boxKeypair(seed: ByteArray?): BoxKeypair {
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        val rc = if (seed == null) {
            sodium.crypto_box_keypair(pk, sk)
        } else {
            sodium.crypto_box_seed_keypair(pk, sk, requireSeed(seed))
        }
        check(rc == 0) { "crypto_box_keypair failed" }
        return BoxKeypair(pk, sk)
    }

    override fun seal(plaintext: ByteArray, recipientPub: ByteArray): ByteArray {
        require(recipientPub.size == 32) { "box public key must be 32 bytes" }
        val out = ByteArray(plaintext.size + SEAL_BYTES)
        check(sodium.crypto_box_seal(out, plaintext, plaintext.size.toLong(), recipientPub) == 0) { "seal failed" }
        return out
    }

    override fun openSealed(ciphertext: ByteArray, pub: ByteArray, secret: ByteArray): ByteArray? {
        if (ciphertext.size < SEAL_BYTES || pub.size != 32 || secret.size != 32) return null
        val out = ByteArray(ciphertext.size - SEAL_BYTES)
        return out.takeIf { sodium.crypto_box_seal_open(out, ciphertext, ciphertext.size.toLong(), pub, secret) == 0 }
    }

    private fun requireSeed(seed: ByteArray): ByteArray =
        seed.also { require(it.size == 32) { "seed must be 32 bytes" } }

    private companion object {
        const val SEAL_BYTES = 48
        val shared: SodiumAndroid by lazy { SodiumAndroid() }
    }
}
