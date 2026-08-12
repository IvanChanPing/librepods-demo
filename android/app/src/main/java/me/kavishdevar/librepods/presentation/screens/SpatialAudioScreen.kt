package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.audio.SpatialAudioDemoEngine
import me.kavishdevar.librepods.audio.SpatialAudioMode
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.utils.FeatureDiagnostics
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SpatialAudioScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val engine = remember { SpatialAudioDemoEngine() }
    var playing by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf(false) }
    var previewStartedHeadTracking by remember { mutableStateOf(false) }
    val mode = state.spatialAudioMode
    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val backdrop = rememberLayerBackdrop()
    val cardShape = RoundedCornerShape(if (materialDesign) 24.dp else 28.dp)
    val topPadding = if (materialDesign) {
        16.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    }
    engine.mode = mode

    LaunchedEffect(mode, playing) {
        if (playing && mode == SpatialAudioMode.HEAD_TRACKED && !previewStartedHeadTracking) {
            viewModel.startHeadTracking()
            previewStartedHeadTracking = true
        } else if ((!playing || mode != SpatialAudioMode.HEAD_TRACKED) && previewStartedHeadTracking) {
            viewModel.stopHeadTracking()
            previewStartedHeadTracking = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            engine.close()
            if (previewStartedHeadTracking) viewModel.stopHeadTracking()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(topPadding))
        Text("Spatial Audio", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Hear a three-dimensional stereo preview and compare Off, Fixed, and Head Tracked modes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Card(
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(if (mode == SpatialAudioMode.OFF) "Stereo Audio" else "Spatial Audio", style = MaterialTheme.typography.titleLarge)
                Text("◖   ●   ◗", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SpatialAudioMode.entries.forEach { option ->
                        TextButton(onClick = { viewModel.setSpatialAudioMode(option) }) {
                            Text(option.label, fontWeight = if (mode == option) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                Button(onClick = {
                    if (playing) {
                        engine.stop()
                        playing = false
                    } else {
                        playbackError = !engine.play()
                        if (playbackError) FeatureDiagnostics.event(context, "spatial_preview_open_failed")
                        playing = !playbackError
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (playing) "Stop preview" else "Play preview")
                }
                if (playbackError) Text("This phone could not open a stereo preview output.", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Scope", fontWeight = FontWeight.SemiBold)
                Text(
                    "These controls drive LibrePods’ built-in preview. System-wide spatialization for other apps requires a compatible rooted audio provider and is not active on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp))
    }
}
