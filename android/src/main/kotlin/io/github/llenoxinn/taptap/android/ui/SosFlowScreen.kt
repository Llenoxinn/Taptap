package io.github.llenoxinn.taptap.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.llenoxinn.taptap.android.R
import io.github.llenoxinn.taptap.android.db.OutboxEntity
import io.github.llenoxinn.taptap.android.mesh.LocationProvider
import io.github.llenoxinn.taptap.mesh.Location
import io.github.llenoxinn.taptap.mesh.Priority
import io.github.llenoxinn.taptap.mesh.SosRequest

data class SosDraft(
    val priority: Priority = Priority.CRITICAL,
    val people: Int = 1,
    val injured: Int = 0,
    val flags: Int = 0,
    val note: String = "",
) {
    fun withPeople(newPeople: Int): SosDraft {
        val clamped = newPeople.coerceIn(1, 255)
        return copy(people = clamped, injured = injured.coerceAtMost(clamped))
    }

    fun withInjuredSelection(selectedInjured: Int): SosDraft {
        val bounded = selectedInjured.coerceIn(0, 255)
        return copy(people = people.coerceAtLeast(bounded).coerceAtLeast(1), injured = bounded)
    }

    fun toggleFlag(flag: Int): SosDraft = copy(flags = flags xor flag)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SosFlowScreen(
    initialDraft: SosDraft,
    initialStep: Int,
    locationProvider: LocationProvider,
    nowEpochS: () -> Long,
    onCancel: () -> Unit,
    onSend: (SosRequest) -> Unit,
) {
    val colors = LocalTaptapColors.current
    val haptic = LocalHapticFeedback.current
    var step by remember { mutableIntStateOf(initialStep) }
    var draft by remember { mutableStateOf(initialDraft) }
    var showNoteField by remember { mutableStateOf(initialDraft.note.isNotEmpty()) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }

    LaunchedEffect(Unit) {
        currentLocation = locationProvider.lastKnownOrNull(nowEpochS())
    }

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
            TextButton(
                onClick = { if (step > 1) step-- else onCancel() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.action_back), color = colors.text, fontSize = 18.sp)
            }
            Text(
                text = "${step.coerceIn(1, 3)} / 3",
                color = colors.textMuted,
                fontSize = 18.sp,
            )
        }

        when (step) {
            1 -> {
                Text(
                    text = stringResource(R.string.sos_step1_title),
                    color = colors.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                PriorityChoiceButton(
                    priority = Priority.CRITICAL,
                    label = stringResource(R.string.priority_critical),
                    onClick = {
                        draft = draft.copy(priority = Priority.CRITICAL)
                        step = 2
                    },
                )
                PriorityChoiceButton(
                    priority = Priority.URGENT,
                    label = stringResource(R.string.priority_urgent),
                    onClick = {
                        draft = draft.copy(priority = Priority.URGENT)
                        step = 2
                    },
                )
                PriorityChoiceButton(
                    priority = Priority.INFO,
                    label = stringResource(R.string.priority_info),
                    onClick = {
                        draft = draft.copy(priority = Priority.INFO)
                        step = 2
                    },
                )
            }

            2 -> {
                Text(
                    text = stringResource(R.string.sos_step2_title),
                    color = colors.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.sos_people_label),
                    color = colors.textMuted,
                    fontSize = 18.sp,
                )
                PeopleStepper(
                    people = draft.people,
                    onChange = { draft = draft.withPeople(it) },
                )

                Text(
                    text = stringResource(R.string.sos_injured_label),
                    color = colors.textMuted,
                    fontSize = 18.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val options = listOf(0 to "0", 1 to "1", 2 to "2", 3 to stringResource(R.string.sos_injured_3_plus))
                    options.forEach { (count, label) ->
                        val selected = if (count == 3) draft.injured >= 3 else draft.injured == count
                        Button(
                            onClick = { draft = draft.withInjuredSelection(count) },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) colors.critical else colors.surface,
                                contentColor = if (selected) Color.White else colors.text,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(text = label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryActionButton(stringResource(R.string.action_next)) { step = 3 }
            }

            else -> {
                Text(
                    text = stringResource(R.string.sos_step3_title),
                    color = colors.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )

                // Location status (never blocks sending)
                val loc = currentLocation
                val locText = if (loc != null && loc.lat != null && loc.lon != null) {
                    stringResource(R.string.sos_location_attached, loc.accuracyM)
                } else {
                    stringResource(R.string.sos_location_pending)
                }
                Text(
                    text = locText,
                    color = if (loc?.lat != null) colors.ok else colors.textMuted,
                    fontSize = 16.sp,
                )

                Text(
                    text = stringResource(R.string.sos_details_optional),
                    color = colors.textMuted,
                    fontSize = 18.sp,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val chips = listOf(
                        OutboxEntity.FLAG_FLOODING to R.string.chip_flooding,
                        OutboxEntity.FLAG_ELDERLY to R.string.chip_elderly,
                        OutboxEntity.FLAG_CHILD to R.string.chip_child,
                        OutboxEntity.FLAG_PREGNANT to R.string.chip_pregnant,
                        OutboxEntity.FLAG_MEDICINE to R.string.chip_medicine,
                    )
                    chips.forEach { (flag, labelRes) ->
                        val selected = (draft.flags and flag) != 0
                        FilterChip(
                            selected = selected,
                            onClick = { draft = draft.toggleFlag(flag) },
                            label = { Text(stringResource(labelRes), fontSize = 18.sp) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }

                if (!showNoteField) {
                    TextButton(
                        onClick = { showNoteField = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = stringResource(R.string.sos_add_note_toggle), color = colors.glow, fontSize = 18.sp)
                    }
                } else {
                    OutlinedTextField(
                        value = draft.note,
                        onValueChange = { if (it.encodeToByteArray().size <= 80) draft = draft.copy(note = it) },
                        label = { Text(stringResource(R.string.sos_note_hint), fontSize = 16.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                TestWarningBanner(R.string.not_monitored)

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val locToSend = currentLocation
                        onSend(
                            SosRequest(
                                priority = draft.priority,
                                lat = locToSend?.lat,
                                lon = locToSend?.lon,
                                accuracyM = locToSend?.accuracyM ?: 0,
                                fixAgeS = locToSend?.fixAgeS ?: 0,
                                people = draft.people,
                                injured = draft.injured,
                                flags = draft.flags,
                                note = draft.note.trim(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = priorityColor(draft.priority, colors),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sos_send_confirm),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PriorityChoiceButton(
    priority: Priority,
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalTaptapColors.current
    val accent = priorityColor(priority, colors)
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.surface,
            contentColor = colors.text,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PriorityShapeIcon(priority = priority, tint = accent, modifier = Modifier.padding(2.dp).height(24.dp).widthIn(min = 24.dp))
            Text(text = label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
        }
    }
}

@Composable
fun PeopleStepper(
    people: Int,
    onChange: (Int) -> Unit,
) {
    val colors = LocalTaptapColors.current
    val decDesc = stringResource(R.string.cd_decrease_people)
    val incDesc = stringResource(R.string.cd_increase_people)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(
            onClick = { if (people > 1) onChange(people - 1) },
            modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 56.dp).semantics {
                contentDescription = decDesc
            },
        ) {
            Text(text = "–", fontSize = 28.sp, color = colors.text)
        }
        Text(
            text = people.toString(),
            color = colors.text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        OutlinedButton(
            onClick = { if (people < 255) onChange(people + 1) },
            modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 56.dp).semantics {
                contentDescription = incDesc
            },
        ) {
            Text(text = "+", fontSize = 28.sp, color = colors.text)
        }
    }
}
