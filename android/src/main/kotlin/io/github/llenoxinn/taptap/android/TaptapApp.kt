package io.github.llenoxinn.taptap.android

import android.app.Application
import io.github.llenoxinn.taptap.android.crypto.DeviceIdentity
import io.github.llenoxinn.taptap.android.crypto.KeystoreSeedStore
import io.github.llenoxinn.taptap.android.crypto.PinnedServerKeys
import io.github.llenoxinn.taptap.android.crypto.SodiumAndroidCrypto
import io.github.llenoxinn.taptap.android.db.TaptapDatabase
import io.github.llenoxinn.taptap.android.mesh.AndroidLocationProvider
import io.github.llenoxinn.taptap.android.mesh.MeshRuntime
import io.github.llenoxinn.taptap.android.mesh.PrefsStore
import io.github.llenoxinn.taptap.mesh.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

data class RelayStatus(
    val running: Boolean = false,
    val radioActive: Boolean = false,
    val lowBattery: Boolean = false,
    val lastError: String? = null,
)

class TaptapApp : Application() {
    val prefsStore: PrefsStore by lazy { PrefsStore(this) }
    val database: TaptapDatabase by lazy { TaptapDatabase.open(this) }
    val crypto: SodiumAndroidCrypto by lazy { SodiumAndroidCrypto() }
    val locationProvider: AndroidLocationProvider by lazy { AndroidLocationProvider(this) }

    val identity: DeviceIdentity by lazy {
        DeviceIdentity(crypto, KeystoreSeedStore(this))
    }

    val meshRuntime: MeshRuntime by lazy {
        MeshRuntime(
            db = database,
            crypto = crypto,
            identityKeys = identity.keypair,
            keyring = PinnedServerKeys.keyring(),
            clock = object : Clock {
                override fun now(): Long = System.currentTimeMillis() / 1000L
            },
            random = SecureRandom().asKotlinRandom(),
            locationProvider = locationProvider,
        )
    }

    private val _relayStatus = MutableStateFlow(RelayStatus())
    val relayStatus: StateFlow<RelayStatus> = _relayStatus.asStateFlow()

    fun updateRelayStatus(transform: (RelayStatus) -> RelayStatus) {
        _relayStatus.update(transform)
    }
}
