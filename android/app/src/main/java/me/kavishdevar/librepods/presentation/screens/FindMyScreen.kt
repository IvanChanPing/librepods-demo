package me.kavishdevar.librepods.presentation.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.findmy.FindMyNetworkDevice
import me.kavishdevar.librepods.findmy.FindMyNetworkLocation
import me.kavishdevar.librepods.findmy.FindMyProviderStatus
import me.kavishdevar.librepods.findmy.OpenBubblesFindMyClient
import me.kavishdevar.librepods.findmy.OpenBubblesFindMyState
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.utils.FeatureDiagnostics
import java.text.DateFormat
import java.util.Date

private data class MapPoint(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val fromNetwork: Boolean = false,
)

@Composable
fun FindMyScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backdrop = rememberLayerBackdrop()
    val state by viewModel.uiState.collectAsState()
    val preferences = remember { context.getSharedPreferences("find_my", Context.MODE_PRIVATE) }
    val providerClient = remember { OpenBubblesFindMyClient(context.applicationContext) }
    val liveProviderState by providerClient.state.collectAsState()
    val providerState = if (BuildConfig.DEMO_MODE) {
        remember { demoFindMyState() }
    } else {
        liveProviderState
    }
    var currentLocation by remember {
        mutableStateOf(if (BuildConfig.DEMO_MODE) demoPhoneLocation() else readLastLocation(context))
    }
    var separationAlerts by remember { mutableStateOf(preferences.getBoolean("separation_alerts", false)) }
    var selectedNetworkId by remember { mutableStateOf<String?>(null) }
    val storedPoint = remember(state.nearbyAirPods?.lastSeen) { readStoredPoint(preferences) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { currentLocation = readLastLocation(context) }
    val pairingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!BuildConfig.DEMO_MODE) providerClient.handlePairingResult(result.resultCode, result.data)
    }
    val networkUiEnabled = FIND_MY_NETWORK_READY || BuildConfig.DEMO_MODE

    if (FIND_MY_NETWORK_READY && !BuildConfig.DEMO_MODE) {
        DisposableEffect(lifecycleOwner, providerClient) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) providerClient.start()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            providerClient.start()
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                providerClient.close()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (BuildConfig.DEMO_MODE) {
            currentLocation = demoPhoneLocation()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            currentLocation = readLastLocation(context)
        }
    }
    LaunchedEffect(state.nearbyAirPods?.lastSeen, currentLocation) {
        val location = currentLocation ?: return@LaunchedEffect
        val nearby = state.nearbyAirPods ?: return@LaunchedEffect
        preferences.edit {
            putLong("last_lat_bits", location.latitude.toBits())
            putLong("last_lon_bits", location.longitude.toBits())
            putLong("last_seen", nearby.lastSeen)
        }
    }
    LaunchedEffect(providerState.devices) {
        if (providerState.devices.none { it.opaqueId == selectedNetworkId }) {
            selectedNetworkId = providerState.devices.firstOrNull()?.opaqueId
        }
    }

    val selectedNetworkDevice = if (networkUiEnabled) {
        providerState.devices.firstOrNull { it.opaqueId == selectedNetworkId }
    } else null
    val networkPoint = selectedNetworkDevice?.location?.let { location ->
        MapPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeMillis = location.timestampMs,
            fromNetwork = true,
        )
    }
    val localPoint = state.nearbyAirPods?.let { nearby ->
        currentLocation?.let { MapPoint(it.latitude, it.longitude, nearby.lastSeen) }
    } ?: storedPoint
    val point = networkPoint ?: localPoint
    val phoneHasUwb = remember {
        BuildConfig.DEMO_MODE || context.packageManager.hasSystemFeature("android.hardware.uwb")
    }
    val ownerSessionAvailable = false
    val rangingMethod = if (phoneHasUwb && ownerSessionAvailable) "UWB precision" else "Bluetooth signal"
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val cardShape = RoundedCornerShape(if (m3eEnabled) 24.dp else 28.dp)
    val topPadding = if (m3eEnabled) {
        16.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    }

    Column(
        Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(topPadding))

        FindMyMap(point = point, current = currentLocation, cardShape = cardShape)

        ProviderControls(
            enabled = networkUiEnabled,
            providerState = providerState,
            connect = {
                if (!BuildConfig.DEMO_MODE) {
                    providerClient.pairingIntent()?.let(pairingLauncher::launch)
                        ?: FeatureDiagnostics.event(context, "find_my_pairing_provider_missing")
                }
            },
            openProvider = { findMyPage ->
                if (!BuildConfig.DEMO_MODE && !providerClient.openProvider(findMyPage)) {
                    FeatureDiagnostics.event(context, "find_my_provider_open_failed")
                }
            },
            openProviderProject = {
                if (!BuildConfig.DEMO_MODE) runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/IvanChanPing/openbubbles-app".toUri(),
                        )
                    )
                }
            },
            refresh = { if (!BuildConfig.DEMO_MODE) providerClient.refresh() },
            disconnect = { if (!BuildConfig.DEMO_MODE) providerClient.disconnect() },
        )

        if (networkUiEnabled && providerState.devices.isNotEmpty()) {
            StyledList(
                title = if (providerState.usingCache) "Devices · Cached" else "Devices",
                description = if (providerState.usingCache) {
                    "Showing the last successful encrypted Find My sync while OpenBubbles is unavailable."
                } else {
                    "Synced by OpenBubbles from the encrypted Find My network."
                },
            ) {
                providerState.devices.forEach { device ->
                    StyledListItem(
                        name = listOf(device.emoji, device.name).filter(String::isNotBlank).joinToString(" "),
                        description = deviceDescription(device),
                        selected = device.opaqueId == selectedNetworkId,
                        onClick = { selectedNetworkId = device.opaqueId },
                    )
                }
            }
        }

        StyledList(
            title = "Selected Device",
            description = if (networkPoint != null) {
                "Location supplied by OpenBubbles Find My. Exact coordinates are never included in diagnostics."
            } else {
                "Local fallback records where this phone last observed the verified AirPods Bluetooth signal."
            },
        ) {
            StyledListItem(
                name = selectedNetworkDevice?.name ?: state.deviceName,
                description = point?.let {
                    val source = if (it.fromNetwork) "Find My network" else "Near this phone"
                    "$source · ${DateFormat.getDateTimeInstance().format(Date(it.timeMillis))}"
                } ?: "No local or network location yet",
                onClick = null,
            )
            StyledListItem(
                name = "Directions",
                description = "Open this last-known point in your maps app.",
                enabled = point != null,
                onClick = point?.let { selectedPoint ->
                    {
                        val opened = runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "geo:${selectedPoint.latitude},${selectedPoint.longitude}?q=${selectedPoint.latitude},${selectedPoint.longitude}".toUri(),
                                )
                            )
                        }.isSuccess
                        FeatureDiagnostics.event(
                            context,
                            if (opened) "find_my_directions_opened" else "find_my_directions_open_failed",
                        )
                    }
                },
            )
            StyledListItem(
                name = "Play Sound",
                description = "Requires the separately verified AirPods case command.",
                enabled = false,
                onClick = null,
            )
        }

        NearbyGuidance(
            rssi = state.nearbyAirPods?.rssi,
            method = rangingMethod,
            phoneHasUwb = phoneHasUwb,
            ownerSessionAvailable = ownerSessionAvailable,
            cardShape = cardShape,
        )

        StyledToggle(
            title = "Alerts",
            label = "Notify when left behind",
            description = "Uses the local verified AirPods BLE signal; OpenBubbles locations remain available on this page.",
            checked = separationAlerts,
            onCheckedChange = {
                separationAlerts = it
                preferences.edit { putBoolean("separation_alerts", it) }
            },
        )

        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp))
    }
}

@Composable
private fun ProviderControls(
    enabled: Boolean,
    providerState: OpenBubblesFindMyState,
    connect: () -> Unit,
    openProvider: (Boolean) -> Unit,
    openProviderProject: () -> Unit,
    refresh: () -> Unit,
    disconnect: () -> Unit,
) {
    if (!enabled) {
        Column(Modifier.alpha(0.55f)) {
            StyledList(
                title = "OpenBubbles Find My · Not ready",
                description = "The client is included for future activation, but Find My Network is disabled in this build.",
            ) {
                StyledListItem(
                    name = "Connect OpenBubbles",
                    description = "Unavailable until the provider integration is ready.",
                    enabled = false,
                    onClick = null,
                )
            }
        }
        return
    }
    StyledList(
        title = "OpenBubbles Find My",
        description = providerStatusDescription(providerState),
    ) {
        when (providerState.status) {
            FindMyProviderStatus.PROVIDER_MISSING -> StyledListItem(
                name = "Get the OpenBubbles provider",
                description = "Install the matching provider build before connecting.",
                onClick = openProviderProject,
            )

            FindMyProviderStatus.UNPAIRED -> StyledListItem(
                name = "Connect OpenBubbles",
                description = "OpenBubbles will show exactly what LibrePods can read before you approve.",
                onClick = connect,
            )

            FindMyProviderStatus.CONNECTING -> StyledListItem(
                name = "Syncing Find My…",
                description = "Waiting for the on-device OpenBubbles provider.",
                onClick = null,
                enabled = false,
            )

            FindMyProviderStatus.PROVIDER_NOT_RUNNING -> StyledListItem(
                name = "Open OpenBubbles",
                description = "Start its encrypted Apple services, then return here.",
                onClick = { openProvider(false) },
            )

            FindMyProviderStatus.ACCOUNT_REQUIRED -> StyledListItem(
                name = "Sign in with OpenBubbles",
                description = "Apple ID and two-factor authentication stay entirely in OpenBubbles.",
                onClick = { openProvider(false) },
            )

            FindMyProviderStatus.KEYCHAIN_REQUIRED,
            FindMyProviderStatus.FIND_MY_SETUP_REQUIRED -> StyledListItem(
                name = "Complete Find My setup",
                description = "Open the existing OpenBubbles keychain and Find My setup page.",
                onClick = { openProvider(true) },
            )

            FindMyProviderStatus.READY -> {
                StyledListItem(
                    name = "Refresh locations",
                    description = "Request a new encrypted Search Party report sync.",
                    onClick = refresh,
                )
                StyledListItem(
                    name = "Disconnect OpenBubbles",
                    description = "Revoke this app's provider capability and clear cached locations.",
                    onClick = disconnect,
                )
            }

            FindMyProviderStatus.SYNC_FAILED,
            FindMyProviderStatus.ERROR -> {
                StyledListItem(
                    name = "Try again",
                    description = providerState.errorCode?.let { "Provider status: $it" }
                        ?: "The last sync did not complete.",
                    onClick = refresh,
                )
                StyledListItem(
                    name = "Open OpenBubbles",
                    description = "Review account and Find My state in the provider.",
                    onClick = { openProvider(true) },
                )
            }
        }
    }
}

private fun providerStatusDescription(state: OpenBubblesFindMyState): String = when (state.status) {
    FindMyProviderStatus.PROVIDER_MISSING -> "The matching OpenBubbles provider is not installed."
    FindMyProviderStatus.UNPAIRED -> "Not connected. Pairing always requires an on-screen approval in OpenBubbles."
    FindMyProviderStatus.CONNECTING -> "Connecting to the on-device provider."
    FindMyProviderStatus.PROVIDER_NOT_RUNNING -> "OpenBubbles must be started so its native Apple services are available."
    FindMyProviderStatus.ACCOUNT_REQUIRED -> "Apple account setup is required in OpenBubbles."
    FindMyProviderStatus.KEYCHAIN_REQUIRED -> "The iCloud keychain clique must be joined or created in OpenBubbles."
    FindMyProviderStatus.FIND_MY_SETUP_REQUIRED -> "OpenBubbles Find My setup is not complete."
    FindMyProviderStatus.READY -> state.refreshedAtMs.takeIf { it > 0 }?.let {
        "Last encrypted sync ${DateFormat.getDateTimeInstance().format(Date(it))}."
    } ?: "OpenBubbles Find My is ready."
    FindMyProviderStatus.SYNC_FAILED -> "Sync failed; any listed devices are the last successful cached result."
    FindMyProviderStatus.ERROR -> "The provider could not complete this request."
}

private fun deviceDescription(device: FindMyNetworkDevice): String {
    val location = device.location
    val seen = location?.let {
        DateFormat.getDateTimeInstance().format(Date(it.timestampMs))
    } ?: "No location report"
    val battery = device.batteryLevel?.let { " · Battery state $it" }.orEmpty()
    val shared = if (device.shared) " · Shared" else ""
    return "$seen$battery$shared"
}

@Composable
private fun NearbyGuidance(
    rssi: Int?,
    method: String,
    phoneHasUwb: Boolean,
    ownerSessionAvailable: Boolean,
    cardShape: RoundedCornerShape,
) {
    val strength = rssi?.let { ((it + 100f) / 60f).coerceIn(0f, 1f) } ?: 0f
    val guidance = when {
        rssi == null -> "Searching for the verified AirPods advertisement…"
        rssi >= -50 -> "Very close"
        rssi >= -65 -> "Close"
        rssi >= -80 -> "Nearby"
        else -> "Farther away"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = cardShape,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Find Nearby", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("$guidance · $method", color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { strength }, modifier = Modifier.fillMaxWidth())
            Text(rssi?.let { "$it dBm" } ?: "No recent signal", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (phoneHasUwb && !ownerSessionAvailable) {
                Text(
                    "This phone has UWB, but the AirPods owner-session parameters are unavailable, so LibrePods automatically uses BLE.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FindMyMap(
    point: MapPoint?,
    current: Location?,
    cardShape: RoundedCornerShape,
) {
    val html = remember(point, current) { mapHtml(point, current) }
    Card(shape = cardShape) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    tag = html
                    loadDataWithBaseURL("https://librepods.local/", html, "text/html", "UTF-8", null)
                }
            },
            update = {
                if (it.tag != html) {
                    it.tag = html
                    it.loadDataWithBaseURL("https://librepods.local/", html, "text/html", "UTF-8", null)
                }
            },
            onRelease = WebView::destroy,
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
    }
}

private fun mapHtml(point: MapPoint?, current: Location?): String {
    val centerLat = point?.latitude ?: current?.latitude ?: 0.0
    val centerLon = point?.longitude ?: current?.longitude ?: 0.0
    val zoom = if (point != null || current != null) 15 else 2
    val markers = buildString {
        current?.let { append("L.circleMarker([${it.latitude},${it.longitude}],{radius:8,color:'#1687ff'}).addTo(map).bindPopup('This phone');") }
        point?.let {
            val label = if (it.fromNetwork) "Find My last known location" else "AirPods last seen near this phone"
            append("L.marker([${it.latitude},${it.longitude}]).addTo(map).bindPopup('$label');")
        }
    }
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <style>html,body,#map{height:100%;margin:0;background:#dfe8ef}</style></head><body><div id="map"></div>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script><script>
        const map=L.map('map',{zoomControl:false}).setView([$centerLat,$centerLon],$zoom);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
        $markers
        </script></body></html>
    """.trimIndent()
}

private fun demoPhoneLocation(): Location = Location("librepods-demo").apply {
    latitude = 51.5072
    longitude = -0.1276
    time = System.currentTimeMillis()
    accuracy = 5f
}

private fun demoFindMyState(nowMillis: Long = System.currentTimeMillis()) = OpenBubblesFindMyState(
    status = FindMyProviderStatus.READY,
    devices = listOf(
        FindMyNetworkDevice(
            opaqueId = "demo-airpods-pro-3",
            name = "Demo AirPods Pro",
            emoji = "🎧",
            productId = 0,
            batteryLevel = 76,
            vendorId = 0,
            model = "AirPods Pro 3",
            systemVersion = "Demo",
            shared = false,
            location = FindMyNetworkLocation(
                latitude = 51.5081,
                longitude = -0.1257,
                horizontalAccuracy = 8,
                status = 1,
                confidence = 100,
                timestampMs = nowMillis - 2 * 60_000L,
            ),
        )
    ),
    refreshedAtMs = nowMillis,
)

private fun readStoredPoint(preferences: android.content.SharedPreferences): MapPoint? {
    if (!preferences.contains("last_lat_bits") || !preferences.contains("last_lon_bits")) return null
    return MapPoint(
        Double.fromBits(preferences.getLong("last_lat_bits", 0L)),
        Double.fromBits(preferences.getLong("last_lon_bits", 0L)),
        preferences.getLong("last_seen", 0L),
    )
}

private fun readLastLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    val manager = context.getSystemService(LocationManager::class.java)
    return manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull(Location::getTime)
}

private const val FIND_MY_NETWORK_READY = false
