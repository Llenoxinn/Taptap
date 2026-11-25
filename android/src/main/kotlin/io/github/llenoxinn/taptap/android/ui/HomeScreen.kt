package io.github.llenoxinn.taptap.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.android.RelayStatus
import io.github.llenoxinn.taptap.android.db.EnvelopeEntity
import io.github.llenoxinn.taptap.android.db.OutboxEntity

@Composable
fun HomeScreen(
    relayStatus: RelayStatus,
    carriedEnvelopes: List<EnvelopeEntity>,
    latestOutbox: OutboxEntity?,
    ownOutboxIds: Set<String>,
    onStartSos: () -> Unit,
    onStartImSafe: () -> Unit,
    onToggleRelay: (Boolean) -> Unit,
    onOpenStatus: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    var showStoreSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Header: app name, relay status dot, Settings icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = colors.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier
                        .background(colors.surface, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val dotColor = if (relayStatus.running) colors.glow else colors.textMuted
                    Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = dotColor) }
                    Text(
                        text = stringResource(
                            if (relayStatus.running) R.string.home_relay_on_badge else R.string.home_relay_off_badge,
                        ),
                        color = if (relayStatus.running) colors.glow else colors.textMuted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val settingsDesc = stringResource(R.string.cd_settings)
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp).semantics { contentDescription = settingsDesc },
            ) {
                Text(
                    text = "⚙",
                    color = colors.text,
                    fontSize = 24.sp,
                )
            }
        }

        // Center SOS button (>= 160dp, first TalkBack focus, no mascot)
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(colors.critical)
                    .semantics { traversalIndex = -1f }
                    .clickable(onClick = onStartSos),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.sos_button),
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            OutlinedButton(
                onClick = onStartImSafe,
                modifier = Modifier.heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_im_safe),
                    color = colors.info,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Disaster mode card + carried count + latest SOS link + permanent Test banner
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.home_disaster_mode),
                        color = colors.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            relayStatus.lowBattery -> stringResource(R.string.relay_low_battery)
                            else -> stringResource(R.string.home_disaster_mode_hint)
                        },
                        color = if (relayStatus.lowBattery) colors.urgent else colors.textMuted,
                        fontSize = 16.sp,
                    )
                }
                Switch(
                    checked = relayStatus.running,
                    onCheckedChange = onToggleRelay,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .clickable { showStoreSheet = true }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.carrying_messages,
                        carriedEnvelopes.size,
                        carriedEnvelopes.size,
                    ),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.home_tap_to_inspect),
                    color = colors.glow,
                    fontSize = 16.sp,
                )
            }

            if (latestOutbox != null) {
                Button(
                    onClick = { onOpenStatus(latestOutbox.msgIdHex) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.surface,
                        contentColor = colors.text,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(text = stringResource(R.string.home_view_last_sos), fontSize = 18.sp)
                }
            }

            Spacer(Modifier.height(4.dp))
            TestWarningBanner(R.string.home_test_banner)
        }
    }

    if (showStoreSheet) {
        CarriedStoreSheet(
            envelopes = carriedEnvelopes,
            ownOutboxIds = ownOutboxIds,
            onDismiss = { showStoreSheet = false },
        )
    }
}
