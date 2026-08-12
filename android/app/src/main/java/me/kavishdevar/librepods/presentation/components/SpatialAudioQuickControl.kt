package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.audio.SpatialAudioMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpatialAudioQuickControl(
    mode: SpatialAudioMode,
    onModeChanged: (SpatialAudioMode) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showModes by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { showModes = true },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(shape = CircleShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text("◉", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Spatial Audio", fontWeight = FontWeight.SemiBold)
                Text(mode.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }

    if (showModes) {
        ModalBottomSheet(onDismissRequest = { showModes = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("Spatial Audio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Choose the mode used by the LibrePods preview.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SpatialAudioMode.entries.forEach { option ->
                        TextButton(onClick = {
                            onModeChanged(option)
                            showModes = false
                        }) { Text(option.label, fontWeight = if (mode == option) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
                TextButton(onClick = {
                    showModes = false
                    onOpenSettings()
                }, modifier = Modifier.align(Alignment.End)) { Text("See & hear how it works") }
            }
        }
    }
}
