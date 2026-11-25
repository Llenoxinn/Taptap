package io.github.llenoxinn.taptap.android

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.llenoxinn.taptap.android.db.OutboxEntity
import io.github.llenoxinn.taptap.android.service.RelayService
import io.github.llenoxinn.taptap.android.ui.HomeScreen
import io.github.llenoxinn.taptap.android.ui.OnboardingScreen
import io.github.llenoxinn.taptap.android.ui.PermissionsHelper
import io.github.llenoxinn.taptap.android.ui.SettingsScreen
import io.github.llenoxinn.taptap.android.ui.SosDraft
import io.github.llenoxinn.taptap.android.ui.SosFlowScreen
import io.github.llenoxinn.taptap.android.ui.StatusScreen
import io.github.llenoxinn.taptap.android.ui.TaptapTheme
import io.github.llenoxinn.taptap.android.util.toHexString
import io.github.llenoxinn.taptap.mesh.Priority
import io.github.llenoxinn.taptap.mesh.SosRequest
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class SosFlow(val draft: SosDraft, val startStep: Int) : Screen
    data class Status(val msgIdHex: String) : Screen
    data object Settings : Screen
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as TaptapApp
        setContent {
            val prefs by app.prefsStore.state.collectAsStateWithLifecycle()
            val relayStatus by app.relayStatus.collectAsStateWithLifecycle()
            val carried by app.meshRuntime.carriedEnvelopes.collectAsStateWithLifecycle(initialValue = emptyList())
            val outboxList by app.meshRuntime.outboxEntries.collectAsStateWithLifecycle(initialValue = emptyList())
            val scope = rememberCoroutineScope()

            var screen by remember {
                mutableStateOf<Screen>(if (prefs.onboardingDone) Screen.Home else Screen.Onboarding)
            }

            val nearbyPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                if (PermissionsHelper.hasNearby(this)) {
                    app.prefsStore.setRelayDesired(true)
                    RelayService.start(this)
                }
            }

            LaunchedEffect(prefs.onboardingDone) {
                if (prefs.onboardingDone && prefs.relayDesired && !relayStatus.running && PermissionsHelper.hasNearby(this@MainActivity)) {
                    RelayService.start(this@MainActivity)
                }
            }

            val ownIds = remember(outboxList) { outboxList.mapTo(HashSet()) { it.msgIdHex } }
            val latestOutbox = outboxList.firstOrNull()

            TaptapTheme(darkTheme = prefs.darkTheme) {
                when (val current = screen) {
                    Screen.Onboarding -> OnboardingScreen(
                        prefs = prefs,
                        onSelectLanguage = { tag ->
                            app.prefsStore.setLanguageTag(tag)
                            PermissionsHelper.applyLocale(tag)
                        },
                        onSetHouseholdSize = { app.prefsStore.setHouseholdSize(it) },
                        onFinish = {
                            app.prefsStore.setOnboardingDone(true)
                            screen = Screen.Home
                        },
                    )

                    Screen.Home -> HomeScreen(
                        relayStatus = relayStatus,
                        carriedEnvelopes = carried,
                        latestOutbox = latestOutbox,
                        ownOutboxIds = ownIds,
                        onStartSos = {
                            screen = Screen.SosFlow(
                                draft = SosDraft(priority = Priority.CRITICAL, people = prefs.householdSize),
                                startStep = 1,
                            )
                        },
                        onStartImSafe = {
                            screen = Screen.SosFlow(
                                draft = SosDraft(
                                    priority = Priority.INFO,
                                    people = prefs.householdSize,
                                    note = getString(R.string.home_im_safe),
                                ),
                                startStep = 3,
                            )
                        },
                        onToggleRelay = { enable ->
                            if (enable) {
                                if (PermissionsHelper.hasNearby(this@MainActivity)) {
                                    app.prefsStore.setRelayDesired(true)
                                    RelayService.start(this@MainActivity)
                                } else {
                                    nearbyPermLauncher.launch(PermissionsHelper.nearbyPermissions())
                                }
                            } else {
                                app.prefsStore.setRelayDesired(false)
                                RelayService.stop(this@MainActivity)
                            }
                        },
                        onOpenStatus = { msgIdHex -> screen = Screen.Status(msgIdHex) },
                        onOpenSettings = { screen = Screen.Settings },
                    )

                    is Screen.SosFlow -> SosFlowScreen(
                        initialDraft = current.draft,
                        initialStep = current.startStep,
                        locationProvider = app.locationProvider,
                        nowEpochS = { app.meshRuntime.clock.now() },
                        onCancel = { screen = Screen.Home },
                        onSend = { req ->
                            scope.launch {
                                val entry = app.meshRuntime.sendSos(req)
                                if (PermissionsHelper.hasNearby(this@MainActivity) && !relayStatus.running) {
                                    app.prefsStore.setRelayDesired(true)
                                    RelayService.start(this@MainActivity)
                                }
                                screen = Screen.Status(entry.msgIdHex)
                            }
                        },
                    )

                    is Screen.Status -> {
                        val entry by app.meshRuntime.observeOutbox(current.msgIdHex)
                            .collectAsStateWithLifecycle(initialValue = outboxList.firstOrNull { it.msgIdHex == current.msgIdHex })
                        StatusScreen(
                            outbox = entry,
                            onBack = { screen = Screen.Home },
                        )
                    }

                    Screen.Settings -> SettingsScreen(
                        prefs = prefs,
                        deviceKeyPrefix = app.identity.keypair.publicKey.toHexString().take(16),
                        onSelectLanguage = { tag ->
                            app.prefsStore.setLanguageTag(tag)
                            PermissionsHelper.applyLocale(tag)
                        },
                        onSetDarkTheme = { app.prefsStore.setDarkTheme(it) },
                        onSetHouseholdSize = { app.prefsStore.setHouseholdSize(it) },
                        onSendTestSos = {
                            scope.launch {
                                val fix = app.locationProvider.lastKnownOrNull(app.meshRuntime.clock.now())
                                val entry = app.meshRuntime.sendSos(
                                    SosRequest(
                                        priority = Priority.CRITICAL,
                                        lat = fix.lat,
                                        lon = fix.lon,
                                        accuracyM = fix.accuracyM,
                                        fixAgeS = fix.fixAgeS,
                                        people = prefs.householdSize,
                                        injured = 0,
                                        flags = OutboxEntity.TEST_FLAG,
                                        note = "TEST",
                                    ),
                                )
                                if (PermissionsHelper.hasNearby(this@MainActivity) && !relayStatus.running) {
                                    app.prefsStore.setRelayDesired(true)
                                    RelayService.start(this@MainActivity)
                                }
                                screen = Screen.Status(entry.msgIdHex)
                            }
                        },
                        onReplayOnboarding = { screen = Screen.Onboarding },
                        onBack = { screen = Screen.Home },
                    )
                }
            }
        }
    }
}
