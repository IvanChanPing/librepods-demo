/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.health.HeartRateSession
import me.kavishdevar.librepods.health.HeartRateSessionState
import me.kavishdevar.librepods.health.RecordedHeartRateSample
import me.kavishdevar.librepods.health.calculateZoneDurations
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.services.HeartRateMonitoringState
import java.text.DateFormat
import java.util.Date

/** Fitness-style analytics backed by persisted validated AirPods samples. */
@Composable
fun HeartRateAnalytics(
    monitoring: HeartRateMonitoringState,
    sessions: HeartRateSessionState,
    onStartSession: () -> Unit,
    onFinishWorkout: () -> Unit,
    onSetZoneBounds: (List<Int>?) -> Unit,
) {
    val session = sessions.current ?: sessions.history.firstOrNull()
    var showZoneEditor by remember { mutableStateOf(false) }

    if (session == null) {
        EmptyHeartRateSession(
            enabled = monitoring.enabled,
            paused = sessions.recordingPaused,
            onStartSession = onStartSession,
        )
    } else {
        SessionSummary(session)
        Spacer(Modifier.height(12.dp))
        HeartRateSessionGraph(
            title = "Heart Rate",
            samples = session.workoutSamples,
        )

        Spacer(Modifier.height(12.dp))
        if (sessions.zoneConfig == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = analyticsCardShape(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Heart rate zones", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Set four personal BPM boundaries to calculate zone time.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showZoneEditor = true }) { Text("Set up") }
                }
            }
        } else {
            HeartRateZones(
                session = session,
                bounds = sessions.zoneConfig.upperBounds,
                onEdit = { showZoneEditor = true },
            )
        }

        if (session.recoverySamples.size >= 2) {
            Spacer(Modifier.height(12.dp))
            HeartRateSessionGraph(
                title = "Post-Workout Heart Rate",
                samples = session.recoverySamples,
                accent = Color(0xFFFF3B30),
            )
        }

        if (sessions.current != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onFinishWorkout,
                enabled = !sessions.current.isRecovering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (sessions.current.isRecovering) "Recording two-minute recovery" else "Finish workout")
            }
        } else if (sessions.recordingPaused) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth()) {
                Text("Start new session")
            }
        }
    }

    if (sessions.history.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("Recent sessions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        sessions.history.take(5).forEach { item ->
            SessionHistoryRow(item)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showZoneEditor) {
        HeartRateZoneEditor(
            existing = sessions.zoneConfig?.upperBounds,
            onDismiss = { showZoneEditor = false },
            onSave = {
                onSetZoneBounds(it)
                showZoneEditor = false
            },
            onClear = {
                onSetZoneBounds(null)
                showZoneEditor = false
            },
        )
    }
}

@Composable
private fun EmptyHeartRateSession(enabled: Boolean, paused: Boolean, onStartSession: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = analyticsCardShape(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Heart Rate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (enabled && !paused) "Waiting for a validated AirPods heart-rate sample."
                else "Start a session to record a full chart, zones, and recovery.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (paused) Button(onClick = onStartSession) { Text("Start session") }
        }
    }
}

@Composable
private fun SessionSummary(session: HeartRateSession) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = analyticsCardShape(),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Avg. Heart Rate", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    session.averageBpm?.toString() ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color(0xFFFF3B30),
                    fontWeight = FontWeight.Medium,
                )
                Text(" BPM", color = Color(0xFFFF3B30), modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(
                "${formatDuration((session.endedAtMillis ?: System.currentTimeMillis()) - session.startedAtMillis)} · " +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(session.startedAtMillis)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeartRateSessionGraph(
    title: String,
    samples: List<RecordedHeartRateSample>,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = analyticsCardShape(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (samples.isNotEmpty()) {
                val min = samples.minOf { it.bpm }
                val max = samples.maxOf { it.bpm }
                Text(
                    "Min $min · Avg ${samples.map { it.bpm }.average().toInt()} · Max $max BPM",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 14.dp)) {
                if (samples.isEmpty()) return@Canvas
                val minimum = (samples.minOf { it.bpm } - 10).coerceAtLeast(30).toFloat()
                val maximum = (samples.maxOf { it.bpm } + 10).coerceAtMost(240).toFloat()
                val span = (maximum - minimum).coerceAtLeast(20f)
                val path = Path()
                samples.forEachIndexed { index, sample ->
                    val x = if (samples.size == 1) size.width / 2f else size.width * index / samples.lastIndex
                    val y = size.height - ((sample.bpm - minimum) / span * size.height)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (samples.size == 1) {
                    drawCircle(accent, radius = 4.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
                } else {
                    drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun HeartRateZones(session: HeartRateSession, bounds: List<Int>, onEdit: () -> Unit) {
    val durations = calculateZoneDurations(session, me.kavishdevar.librepods.health.HeartRateZoneConfig(bounds))
    val colors = listOf(Color(0xFF45A7F5), Color(0xFF46D7C5), Color(0xFF9DE000), Color(0xFFFF9500), Color(0xFFFF2D55))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = analyticsCardShape(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Heart rate zones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onEdit) { Text("Edit") }
            }
            durations.forEachIndexed { index, zone ->
                val range = when (index) {
                    0 -> "≤${bounds[0]} BPM"
                    4 -> "${bounds[3] + 1}+ BPM"
                    else -> "${bounds[index - 1] + 1}–${bounds[index]} BPM"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Zone ${index + 1}", color = colors[index], modifier = Modifier.weight(1f))
                    Text(formatDuration(zone.durationMillis), modifier = Modifier.weight(1f))
                    Text(range, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryRow(session: HeartRateSession) {
    Card(
        shape = analyticsCardShape(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(session.startedAtMillis)))
                Text(formatDuration((session.endedAtMillis ?: session.startedAtMillis) - session.startedAtMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${session.averageBpm ?: "—"} BPM", color = Color(0xFFFF3B30), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HeartRateZoneEditor(
    existing: List<Int>?,
    onDismiss: () -> Unit,
    onSave: (List<Int>) -> Unit,
    onClear: () -> Unit,
) {
    var values by remember(existing) {
        mutableStateOf((existing ?: List(4) { 0 }).map { if (it == 0) "" else it.toString() })
    }
    val parsed = values.map { it.toIntOrNull() }
    val valid = parsed.all { it != null && it in 40..240 } &&
        parsed.filterNotNull().zipWithNext().all { (left, right) -> left < right }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart rate zones") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the upper BPM boundary for Zones 1–4. Zone 5 contains higher readings.")
                values.forEachIndexed { index, value ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { updated ->
                            values = values.toMutableList().also { it[index] = updated.filter(Char::isDigit).take(3) }
                        },
                        label = { Text("Zone ${index + 1} upper BPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parsed.filterNotNull()) }, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun analyticsCardShape(): RoundedCornerShape =
    RoundedCornerShape(
        if (LocalDesignSystem.current == DesignSystem.Material) 24.dp else 28.dp
    )

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
