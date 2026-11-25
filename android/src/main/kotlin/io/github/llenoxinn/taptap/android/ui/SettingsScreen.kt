package io.github.llenoxinn.taptap.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.android.mesh.UserPrefs

@Composable
fun SettingsScreen(
    prefs: UserPrefs,
    deviceKeyPrefix: String,
    onSelectLanguage: (String) -> Unit,
    onSetDarkTheme: (Boolean) -> Unit,
    onSetHouseholdSize: (Int) -> Unit,
    onSendTestSos: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = stringResource(R.string.action_back), color = colors.text, fontSize = 18.sp)
        }

        Text(
            text = stringResource(R.string.settings_title),
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )

        // Language
        Text(text = stringResource(R.string.settings_language), color = colors.textMuted, fontSize = 18.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("ceb" to R.string.lang_ceb, "fil" to R.string.lang_fil, "en" to R.string.lang_en)
                .forEach { (tag, labelRes) ->
                    val selected = prefs.languageTag == tag
                    Button(
                        onClick = { onSelectLanguage(tag) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) colors.glow else colors.surface,
                            contentColor = if (selected) androidx.compose.ui.graphics.Color(0xFF0E1116) else colors.text,
                        ),
                    ) {
                        Text(text = stringResource(labelRes), fontSize = 16.sp)
                    }
                }
        }

        // Theme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = stringResource(R.string.settings_theme), color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(if (prefs.darkTheme) R.string.settings_theme_dark else R.string.settings_theme_light),
                    color = colors.textMuted,
                    fontSize = 16.sp,
                )
            }
            Switch(
                checked = prefs.darkTheme,
                onCheckedChange = onSetDarkTheme,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        // Household size
        Text(text = stringResource(R.string.settings_household), color = colors.textMuted, fontSize = 18.sp)
        PeopleStepper(people = prefs.householdSize, onChange = onSetHouseholdSize)

        // Battery optimisation
        OutlinedButton(
            onClick = { PermissionsHelper.openBatteryExemption(context) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(text = stringResource(R.string.settings_battery_guide), fontSize = 18.sp)
        }

        // Send Test SOS
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_send_test_sos),
                color = colors.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_send_test_sos_desc),
                color = colors.textMuted,
                fontSize = 16.sp,
            )
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSendTestSos()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.urgent,
                    contentColor = androidx.compose.ui.graphics.Color(0xFF0E1116),
                ),
            ) {
                Text(text = stringResource(R.string.settings_send_test_sos), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Privacy & About
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_about),
                color = colors.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.onboarding_privacy_body),
                color = colors.textMuted,
                fontSize = 16.sp,
            )
            Text(
                text = stringResource(R.string.settings_device_key, deviceKeyPrefix),
                color = colors.textMuted,
                fontSize = 15.sp,
            )
            TextButton(onClick = onReplayOnboarding, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = stringResource(R.string.settings_replay_onboarding), color = colors.glow, fontSize = 16.sp)
            }
        }
    }
}
