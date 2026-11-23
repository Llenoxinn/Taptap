package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.crypto.SodiumJvmCrypto

/** Real libsodium for tests (test-only dependency; mesh-core main stays crypto-free). */
internal val crypto: Crypto = SodiumJvmCrypto()
