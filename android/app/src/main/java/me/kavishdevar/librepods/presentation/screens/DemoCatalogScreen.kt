package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.navigation.Screen

/**
 * Launcher page for the separately installed "LibrePods Demo" build.
 *
 * Every destination uses the real production composable with a fixture-backed ViewModel. The blue
 * status card makes the test boundary explicit: no AirPods connection or Bluetooth service exists.
 */
@Composable
fun DemoCatalogScreen(onOpen: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // blueDemoStatusCard — rounded blue card at the top; identifies fixture data and radio isolation.
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Demo data", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Bluetooth is disabled in this app. AirPods values and device-control actions use local fixture state.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        DemoScreenGroup(
            title = "Primary flow",
            entries = listOf(
                "AirPods settings" to Screen.AirPodsSettings,
                "App settings" to Screen.AppSettings,
                "Onboarding" to Screen.Onboarding,
                "Find My" to Screen.FindMy,
                "Release notes" to Screen.ReleaseNotes,
                "Troubleshooting" to Screen.Troubleshooting,
                "Loading state" to Screen.LoadingPreview,
            ),
            onOpen = onOpen,
        )

        DemoScreenGroup(
            title = "Audio and health",
            entries = listOf(
                "Spatial Audio" to Screen.SpatialAudio,
                "Microphone" to Screen.MicrophoneSettings,
                "Head tracking" to Screen.HeadTracking,
                "Heart rate" to Screen.HeartRateTest,
                "Equalizer" to Screen.Equalizer,
                "Adaptive Audio strength" to Screen.AdaptiveStrength,
                "Hearing protection" to Screen.HearingProtection,
                "Hearing aid" to Screen.HearingAid,
                "Hearing aid adjustments" to Screen.HearingAidAdjustments,
                "Update hearing test" to Screen.UpdateHearingTest,
                "Transparency customization" to Screen.TransparencyCustomization,
            ),
            onOpen = onOpen,
        )

        DemoScreenGroup(
            title = "Device controls",
            entries = listOf(
                "Accessibility" to Screen.Accessibility,
                "Rename" to Screen.Rename,
                "Version information" to Screen.VersionInfo,
                "Left press and hold" to Screen.LongPress("Left"),
                "Right press and hold" to Screen.LongPress("Right"),
                "Answer call control" to Screen.CallControl("Answer Calls"),
                "End call control" to Screen.CallControl("End Calls"),
            ),
            onOpen = onOpen,
        )

        DemoScreenGroup(
            title = "About and purchase",
            entries = listOf(
                "Purchase" to Screen.Purchase,
                "Open-source licenses" to Screen.OpenSourceLicenses,
            ),
            onOpen = onOpen,
        )

        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp))
    }
}

@Composable
private fun DemoScreenGroup(
    title: String,
    entries: List<Pair<String, Screen>>,
    onOpen: (Screen) -> Unit,
) {
    StyledList(title = title) {
        entries.forEach { (label, screen) ->
            StyledListItem(
                name = label,
                description = "Open with deterministic demo state",
                onClick = { onOpen(screen) },
            )
        }
    }
}
