package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.HighResolutionMicState

@Composable
fun MicrophoneSettingsRoute(
    viewModel: AirPodsViewModel
) {
    val state by viewModel.uiState.collectAsState()

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE

    Box (
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        MicrophoneSettingsScreen(
            selectedMode = state.controlStates[id]?.getOrNull(0)?.toInt() ?: 0,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            onMicrophoneSettingsChanged = {
                viewModel.setControlCommandInt(id, it)
            },
            highResolutionMic = state.highResolutionMic,
            onStartHighResolutionMic = viewModel::startHighResolutionMicrophone,
            onStopHighResolutionMic = viewModel::stopHighResolutionMicrophone,
            onRecordSample = viewModel::recordHighResolutionMicrophoneSample,
            onPlaySample = viewModel::playHighResolutionMicrophoneSample,
        )
    }
}

@Composable
fun MicrophoneSettingsScreen(
    selectedMode: Int,
    topPadding: Dp = 16.dp,
    bottomPadding: Dp = 16.dp,
    onMicrophoneSettingsChanged: (Int) -> Unit,
    highResolutionMic: HighResolutionMicState,
    onStartHighResolutionMic: () -> Unit,
    onStopHighResolutionMic: () -> Unit,
    onRecordSample: () -> Unit,
    onPlaySample: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(scrollState)
            .padding(top = 8.dp)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        StyledList {
            StyledListItem(
                name = stringResource(R.string.microphone_automatic),
                selected = selectedMode == 0,
                onClick = { onMicrophoneSettingsChanged(0) }
            )

            StyledListItem(
                name = stringResource(R.string.microphone_always_right),
                selected = selectedMode == 1,
                onClick = { onMicrophoneSettingsChanged(1) }
            )

            StyledListItem(
                name = stringResource(R.string.microphone_always_left),
                selected = selectedMode == 2,
                onClick = { onMicrophoneSettingsChanged(2) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("High-Resolution Microphone", fontWeight = FontWeight.SemiBold)
                Text(
                    "Decode the AirPods AAC-ELD stream inside LibrePods. This does not replace Android’s system microphone for calls or other apps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { highResolutionMic.peak },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val status = when {
                    highResolutionMic.starting -> "Opening AAC-ELD decoder…"
                    highResolutionMic.active -> "Live · ${highResolutionMic.sampleRate} Hz · ${highResolutionMic.channelCount} channel"
                    highResolutionMic.error != null -> highResolutionMic.error
                    else -> "Stopped"
                }
                Text(status, color = if (highResolutionMic.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = if (highResolutionMic.active) onStopHighResolutionMic else onStartHighResolutionMic,
                    enabled = !highResolutionMic.starting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (highResolutionMic.active) "Stop microphone" else "Start microphone")
                }
                if (highResolutionMic.active) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onRecordSample,
                            enabled = !highResolutionMic.recording,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (highResolutionMic.recording) "Recording…" else "Record 5 sec") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onPlaySample,
                            enabled = highResolutionMic.recordedClip != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Play sample") }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
