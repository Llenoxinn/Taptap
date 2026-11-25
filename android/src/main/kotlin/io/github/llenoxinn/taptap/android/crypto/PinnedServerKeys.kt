package io.github.llenoxinn.taptap.android.crypto

import io.github.llenoxinn.taptap.android.util.hexToBytes
import io.github.llenoxinn.taptap.mesh.ServerKeyring
import io.github.llenoxinn.taptap.mesh.ServerKeys

/**
 * Pinned server public keys (no secret keys in the app; AGENTS.md G4).
 * M2 pins the test server public keys matching mesh-core golden vectors (`keyId = 1`).
 */
object PinnedServerKeys {
    private const val KEY_ID = 1
    private const val SIGN_PUB_HEX = "29acbae141bccaf0b22e1a94d34d0bc7361e526d0bfe12c89794bc9322966dd7"
    private const val BOX_PUB_HEX = "f14b5173130a1b80687f273d49e8f4740a793a949b83b105837f2a61e8fee14f"

    fun keyring(): ServerKeyring = ServerKeyring(
        keys = mapOf(KEY_ID to ServerKeys(SIGN_PUB_HEX.hexToBytes(), BOX_PUB_HEX.hexToBytes())),
        currentKeyId = KEY_ID,
    )
}
