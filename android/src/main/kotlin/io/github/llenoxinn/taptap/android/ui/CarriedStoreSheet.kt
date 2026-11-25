package io.github.llenoxinn.taptap.android.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.android.db.EnvelopeEntity
import io.github.llenoxinn.taptap.mesh.Kind
import io.github.llenoxinn.taptap.mesh.Priority
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarriedStoreSheet(
    envelopes: List<EnvelopeEntity>,
    ownOutboxIds: Set<String>,
    onDismiss: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    val context = LocalContext.current
    val timeFmt = DateFormat.getTimeFormat(context)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.bg,
        contentColor = colors.text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.store_sheet_title, envelopes.size),
                color = colors.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )

            if (envelopes.isEmpty()) {
                Text(
                    text = stringResource(R.string.store_sheet_empty),
                    color = colors.textMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(envelopes, key = { it.msgIdHex }) { env ->
                        val priority = Priority.entries.getOrElse(env.priority) { Priority.INFO }
                        val kind = Kind.entries.getOrElse(env.kind) { Kind.SOS }
                        val kindLabel = when (kind) {
                            Kind.SOS -> stringResource(R.string.store_row_kind_sos)
                            Kind.UPDATE -> stringResource(R.string.store_row_kind_update)
                            Kind.ACK -> stringResource(R.string.store_row_kind_ack)
                            Kind.RECEIPT -> stringResource(R.string.store_row_kind_receipt)
                        }
                        val prioLabel = when (priority) {
                            Priority.CRITICAL -> stringResource(R.string.priority_short_critical)
                            Priority.URGENT -> stringResource(R.string.priority_short_urgent)
                            Priority.INFO -> stringResource(R.string.priority_short_info)
                        }
                        val timeText = timeFmt.format(Date(env.createdAt * 1000L))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface, RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PriorityShapeIcon(priority, priorityColor(priority, colors))
                                Text(
                                    text = "$prioLabel · $kindLabel",
                                    color = colors.text,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (env.msgIdHex in ownOutboxIds) {
                                    Text(
                                        text = "· " + stringResource(R.string.store_row_yours),
                                        color = colors.glow,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(
                                    R.string.store_row_meta,
                                    env.msgIdHex.take(12),
                                    env.hops,
                                    timeText,
                                ),
                                color = colors.textMuted,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(text = stringResource(R.string.action_close), fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
