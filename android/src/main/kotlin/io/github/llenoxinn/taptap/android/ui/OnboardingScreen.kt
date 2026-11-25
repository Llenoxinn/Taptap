package io.github.llenoxinn.taptap.android.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.android.mesh.UserPrefs

@Composable
fun OnboardingScreen(
    prefs: UserPrefs,
    onSelectLanguage: (String) -> Unit,
    onSetHouseholdSize: (Int) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var permTick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permTick++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        when (step) {
            0 -> {
                Text(
                    text = stringResource(R.string.onboarding_language_title),
                    color = colors.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                LanguageChoiceButton("ceb", R.string.lang_ceb, prefs.languageTag == "ceb") {
                    onSelectLanguage("ceb")
                }
                LanguageChoiceButton("fil", R.string.lang_fil, prefs.languageTag == "fil") {
                    onSelectLanguage("fil")
                }
                LanguageChoiceButton("en", R.string.lang_en, prefs.languageTag == "en") {
                    onSelectLanguage("en")
                }
                Spacer(Modifier.height(8.dp))
                PrimaryActionButton(stringResource(R.string.action_continue)) { step = 1 }
            }

            1 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(16.dp))
                    FireflyMascot()
                    Text(
                        text = stringResource(R.string.onboarding_welcome_title),
                        color = colors.text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_welcome_body),
                        color = colors.textMuted,
                        fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                PrimaryActionButton(stringResource(R.string.action_continue)) { step = 2 }
            }

            2 -> {
                Text(
                    text = stringResource(R.string.onboarding_test_title),
                    color = colors.critical,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.onboarding_test_warning),
                    color = colors.text,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(14.dp))
                        .padding(18.dp),
                )
                PrimaryActionButton(stringResource(R.string.onboarding_i_understand)) { step = 3 }
            }

            3 -> {
                val _refresh = permTick
                Text(
                    text = stringResource(R.string.onboarding_permissions_title),
                    color = colors.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                PermissionRowCard(
                    title = stringResource(R.string.perm_nearby_title),
                    why = stringResource(R.string.perm_nearby_why),
                    granted = PermissionsHelper.hasNearby(context),
                    onAllow = { permLauncher.launch(PermissionsHelper.nearbyPermissions()) },
                )
                PermissionRowCard(
                    title = stringResource(R.string.perm_location_title),
                    why = stringResource(R.string.perm_location_why),
                    granted = PermissionsHelper.hasLocation(context),
                    onAllow = { permLauncher.launch(PermissionsHelper.locationPermissions()) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRowCard(
                        title = stringResource(R.string.perm_notifications_title),
                        why = stringResource(R.string.perm_notifications_why),
                        granted = PermissionsHelper.hasNotifications(context),
                        onAllow = { permLauncher.launch(PermissionsHelper.notificationPermissions()) },
                    )
                }
                val allDone = PermissionsHelper.hasNearby(context) &&
                    PermissionsHelper.hasLocation(context) &&
                    PermissionsHelper.hasNotifications(context)
                PrimaryActionButton(
                    text = stringResource(if (allDone) R.string.action_continue else R.string.perm_not_now),
                ) { step = 4 }
            }

            4 -> {
                var ignored by remember { mutableStateOf(PermissionsHelper.isIgnoringBatteryOptimizations(context)) }
                Text(
                    text = stringResource(R.string.onboarding_battery_title),
                    color = colors.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.onboarding_battery_body),
                    color = colors.textMuted,
                    fontSize = 18.sp,
                )
                OutlinedButton(
                    onClick = {
                        PermissionsHelper.openBatteryExemption(context)
                        ignored = PermissionsHelper.isIgnoringBatteryOptimizations(context)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (ignored) R.string.onboarding_battery_already else R.string.onboarding_battery_open,
                        ),
                        fontSize = 18.sp,
                    )
                }
                PrimaryActionButton(stringResource(R.string.action_continue)) { step = 5 }
            }

            5 -> {
                Text(
                    text = stringResource(R.string.onboarding_household_title),
                    color = colors.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.onboarding_household_body),
                    color = colors.textMuted,
                    fontSize = 18.sp,
                )
                PeopleStepper(
                    people = prefs.householdSize,
                    onChange = onSetHouseholdSize,
                )
                PrimaryActionButton(stringResource(R.string.action_continue)) { step = 6 }
            }

            else -> {
                Text(
                    text = stringResource(R.string.onboarding_privacy_title),
                    color = colors.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.onboarding_privacy_body),
                    color = colors.text,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                )
                PrimaryActionButton(stringResource(R.string.action_finish), onClick = onFinish)
            }
        }
    }
}

@Composable
private fun LanguageChoiceButton(
    tag: String,
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) colors.glow else colors.surface,
            contentColor = if (selected) androidx.compose.ui.graphics.Color(0xFF0E1116) else colors.text,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text = stringResource(labelRes), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PermissionRowCard(
    title: String,
    why: String,
    granted: Boolean,
    onAllow: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(text = why, color = colors.textMuted, fontSize = 18.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onAllow,
                enabled = !granted,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(if (granted) R.string.perm_allowed else R.string.perm_allow),
                    fontSize = 18.sp,
                )
            }
        }
    }
}

@Composable
fun PrimaryActionButton(text: String, onClick: () -> Unit) {
    val colors = LocalTaptapColors.current
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.glow,
            contentColor = androidx.compose.ui.graphics.Color(0xFF0E1116),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
