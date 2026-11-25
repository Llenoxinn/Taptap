package io.github.llenoxinn.taptap.android.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.android.db.OutboxEntity
import io.github.llenoxinn.taptap.mesh.AckStatus
import io.github.llenoxinn.taptap.mesh.Priority
import java.util.Date

@Composable
fun StatusScreen(
    outbox: OutboxEntity?,
    onBack: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    val context = LocalContext.current
    val fmt = DateFormat.getTimeFormat(context)
    fun formatTs(epochS: Long?): String? = epochS?.let { fmt.format(Date(it * 1000L)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = stringResource(R.string.action_back), color = colors.text, fontSize = 18.sp)
            }
            if (outbox?.isTest == true) {
                Text(
                    text = stringResource(R.string.sos_test_mode_tag),
                    color = colors.glow,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(colors.surface, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.status_screen_title),
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )

        if (outbox != null) {
            val priority = Priority.entries.getOrElse(outbox.priority) { Priority.CRITICAL }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PriorityShapeIcon(priority, priorityColor(priority, colors))
                Text(
                    text = stringResource(R.string.status_id_label, outbox.msgIdHex.take(12)),
                    color = colors.textMuted,
                    fontSize = 16.sp,
                )
            }

            // 1. Saved
            StatusStepRow(
                reached = true,
                accentColor = colors.glow,
                title = stringResource(R.string.status_saved),
                subtitle = stringResource(R.string.status_saved_detail),
                timeText = formatTs(outbox.createdAt),
            )

            // 2. Passed on (local nearby peers only)
            val passedReached = outbox.passedCount > 0
            StatusStepRow(
                reached = passedReached,
                accentColor = colors.glow,
                title = if (passedReached) {
                    pluralStringResource(R.plurals.status_passed_count, outbox.passedCount, outbox.passedCount)
                } else {
                    stringResource(R.string.status_passed_title)
                },
                subtitle = if (passedReached) null else stringResource(R.string.status_passed_waiting),
                timeText = formatTs(outbox.passedAt),
            )

            // 3. Delivered — ONLY when verified server RECEIPT (or ACK) exists (Rule P4)
            val delivered = outbox.isDelivered
            StatusStepRow(
                reached = delivered,
                accentColor = colors.ok,
                title = stringResource(R.string.status_delivered_title),
                subtitle = stringResource(
                    if (delivered) R.string.status_delivered_detail else R.string.status_delivered_waiting,
                ),
                timeText = formatTs(outbox.receiptAt ?: outbox.ackAt),
            )

            // 4. Acknowledged — ONLY when verified server ACK exists (Rule P4)
            val acked = outbox.ackAt != null
            val ackTitle = when (outbox.ackStatus) {
                AckStatus.ACKNOWLEDGED.ordinal -> stringResource(R.string.status_ack)
                AckStatus.RESOLVED.ordinal -> stringResource(R.string.status_ack_resolved)
                AckStatus.FALSE.ordinal -> stringResource(R.string.status_ack_false)
                else -> stringResource(R.string.status_ack_waiting)
            }
            val ackColor = if (outbox.ackStatus == AckStatus.FALSE.ordinal) colors.urgent else colors.ok
            StatusStepRow(
                reached = acked,
                accentColor = ackColor,
                title = ackTitle,
                subtitle = null,
                timeText = formatTs(outbox.ackAt),
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.status_guidance),
            color = colors.textMuted,
            fontSize = 18.sp,
        )
        TestWarningBanner(R.string.not_monitored)
    }
}

@Composable
private fun StatusStepRow(
    reached: Boolean,
    accentColor: Color,
    title: String,
    subtitle: String?,
    timeText: String?,
) {
    val colors = LocalTaptapColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            if (reached) {
                drawCircle(color = accentColor)
            } else {
                drawCircle(color = colors.textMuted, style = Stroke(width = 4f))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = if (reached) colors.text else colors.textMuted,
                    fontSize = 19.sp,
                    fontWeight = if (reached) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (reached && timeText != null) {
                    Text(
                        text = timeText,
                        color = colors.textMuted,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (subtitle != null) {
                Text(text = subtitle, color = colors.textMuted, fontSize = 16.sp)
            }
        }
    }
}
