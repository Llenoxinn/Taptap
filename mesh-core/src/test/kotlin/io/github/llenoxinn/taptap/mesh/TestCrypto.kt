package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.crypto.SodiumJvmCrypto
import kotlin.random.Random

/** Real libsodium for tests (test-only dependency; mesh-core main stays crypto-free). */
internal val crypto: Crypto = SodiumJvmCrypto()

internal const val SERVER_KEY_ID: Int = 1

internal fun serverSecret(n: Int = 100): ServerSecret = ServerSecret(crypto.signKeypair(seed(n)), crypto.boxKeypair(seed(n + 1)))

internal fun serverCodec(random: Random = Random(7)): ServerCodec =
    ServerCodec(crypto, mapOf(SERVER_KEY_ID to serverSecret()), SERVER_KEY_ID, random)

internal fun factory(clock: Clock, deviceSeed: Int = 1, random: Random = Random(deviceSeed)): EnvelopeFactory =
    EnvelopeFactory(crypto, crypto.signKeypair(seed(deviceSeed)), serverCodec().keyring, clock, random)

internal fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
