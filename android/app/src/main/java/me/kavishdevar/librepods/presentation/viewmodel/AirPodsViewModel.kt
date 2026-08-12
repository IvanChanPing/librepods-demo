/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.audio.SpatialAudioMode
import me.kavishdevar.librepods.audio.AacEldDecoder
import me.kavishdevar.librepods.audio.PcmClip
import me.kavishdevar.librepods.audio.PcmPreviewPlayer
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTCCCDHandles
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.health.HealthConnectExportState
import me.kavishdevar.librepods.health.HealthConnectExportStatus
import me.kavishdevar.librepods.health.HeartRateSessionState
import me.kavishdevar.librepods.health.HeartRateSession
import me.kavishdevar.librepods.health.HeartRateZoneConfig
import me.kavishdevar.librepods.health.RecordedHeartRateSample
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.services.HeartRateMonitoringState
import me.kavishdevar.librepods.services.HeartRateMonitoringStatus
import me.kavishdevar.librepods.utils.FeatureDiagnostics
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("ArrayInDataClass")
data class AirPodsUiState(
    val deviceName: String = "AirPods",

    val isLocallyConnected: Boolean = false,

    val instance: AirPodsInstance? = null,
    val capabilities: Set<Capability> = emptySet(),

    val controlStates: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val offListeningMode: Boolean = true,

    val battery: List<Battery> = emptyList(),
    val ancMode: Int = 3,

    val modelName: String = "",
    val actualModel: String = "",
    val serialNumbers: List<String> = emptyList(),
    val version1: String = "",
    val version2: String = "",
    val version3: String = "",

    val headTrackingActive: Boolean = false,
    val headGesturesEnabled: Boolean = true,
    val spatialAudioMode: SpatialAudioMode = SpatialAudioMode.OFF,
    val highResolutionMic: HighResolutionMicState = HighResolutionMicState(),
    val nearbyAirPods: BLEManager.AirPodsStatus? = null,

    val heartRate: HeartRateMonitoringState = HeartRateMonitoringState(),
    val heartRateSessions: HeartRateSessionState = HeartRateSessionState(),
    val healthConnect: HealthConnectExportState = HealthConnectExportState(),

    val eqData: FloatArray = floatArrayOf(),

    val automaticEarDetectionEnabled: Boolean = true,
    val automaticConnectionEnabled: Boolean = true,

    val leftAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    val rightAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,

    val loudSoundReductionEnabled: Boolean = false,
    val transparencyData: ByteArray = byteArrayOf(),
    val hearingAidData: ByteArray = byteArrayOf(),

    val isPremium: Boolean = false,
    val vendorIdHook: Boolean = false,

    val dynamicEndOfCharge: Boolean = false,

    val connectionSuccessful: Boolean = false,
    val timeUntilFOSSPremiumExpiry: Long = 0L,

    val customEq: CustomEq = CustomEq(1, 50, 50, 50) // disabled
)

data class HighResolutionMicState(
    val starting: Boolean = false,
    val active: Boolean = false,
    val peak: Float = 0f,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val recording: Boolean = false,
    val recordedClip: PcmClip? = null,
    val error: String? = null,
)

val demoInstance = AirPodsInstance(
    name = "AirPods Pro",
    model = AirPodsModels.getModelByModelNumber("A3064")!!,
    actualModelNumber = "A3064",
    serialNumber = "JXF9Q94A40",
    leftSerialNumber = "L-DEMO",
    rightSerialNumber = "R-DEMO",
    version1 = "90.3388000000000000.1786",
    version2 = "90.3388000000000000.1786",
    version3 = "9441861",
)

/** Deterministic, local-only fixture used by previews and the separately packaged Demo app. */
internal fun createDemoState(nowMillis: Long = System.currentTimeMillis()): AirPodsUiState {
    val bpmPattern = listOf(112, 118, 124, 132, 141, 149, 158, 166, 154, 146, 138, 131)
    val liveSamples = bpmPattern.mapIndexed { index, bpm ->
        HeartRateSample(
            bpm = bpm,
            sequence = index,
            receivedAtMillis = nowMillis - (bpmPattern.lastIndex - index) * 1_000L,
            receivedAtElapsedRealtime = 0L,
        )
    }
    val workoutStart = nowMillis - 30 * 60_000L
    val workoutEnd = nowMillis - 2 * 60_000L
    val recordedSamples = (0..60).map { index ->
        val recordedAt = workoutStart + index * 30_000L
        RecordedHeartRateSample(
            bpm = bpmPattern[index % bpmPattern.size],
            recordedAtMillis = recordedAt,
        )
    }
    val currentSession = HeartRateSession(
        id = workoutStart,
        startedAtMillis = workoutStart,
        workoutEndedAtMillis = workoutEnd,
        samples = recordedSamples,
    )
    val previousStart = nowMillis - 26 * 60 * 60_000L
    val previousSession = HeartRateSession(
        id = previousStart,
        startedAtMillis = previousStart,
        endedAtMillis = previousStart + 24 * 60_000L,
        samples = recordedSamples.take(40).mapIndexed { index, sample ->
            sample.copy(recordedAtMillis = previousStart + index * 36_000L)
        },
    )

    return AirPodsUiState(
    deviceName = demoInstance.name,

    isLocallyConnected = true,

    instance = demoInstance,
    capabilities = Capability.entries.toSet(),

    battery = listOf(
        Battery(BatteryComponent.LEFT, 80, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.RIGHT, 18, BatteryStatus.CHARGING),
        Battery(BatteryComponent.CASE, 76, BatteryStatus.NOT_CHARGING)
    ),

    ancMode = 3,
    offListeningMode = false,

    modelName = demoInstance.model.displayName,
    actualModel = demoInstance.actualModelNumber,
    serialNumbers =  listOf(
        demoInstance.serialNumber?: "",
        demoInstance.leftSerialNumber?: "",
        demoInstance.rightSerialNumber?: ""
    ),

    version1 = demoInstance.version1?: "",
    version2 = demoInstance.version2?: "",
    version3 = demoInstance.version3?: "",

    headTrackingActive = true,
    headGesturesEnabled = true,
    spatialAudioMode = SpatialAudioMode.HEAD_TRACKED,
    highResolutionMic = HighResolutionMicState(
        peak = 0.42f,
        sampleRate = 24_000,
        channelCount = 1,
    ),
    nearbyAirPods = BLEManager.AirPodsStatus(
        address = "00:00:00:00:00:00",
        rssi = -48,
        lastSeen = nowMillis,
        paired = true,
        model = "AirPods Pro 3 · Demo",
        leftBattery = 80,
        rightBattery = 78,
        caseBattery = 76,
        isLeftInEar = true,
        isRightInEar = true,
        connectionState = "Demo",
    ),
    heartRate = HeartRateMonitoringState(
        enabled = true,
        status = HeartRateMonitoringStatus.LIVE,
        samples = liveSamples,
    ),
    heartRateSessions = HeartRateSessionState(
        current = currentSession,
        history = listOf(previousSession),
        zoneConfig = HeartRateZoneConfig(listOf(136, 149, 161, 174)),
    ),
    healthConnect = HealthConnectExportState(
        enabled = false,
        status = HealthConnectExportStatus.READY,
        detailedSamples = false,
    ),

    automaticEarDetectionEnabled = true,
    automaticConnectionEnabled = true,

    leftAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    rightAction = StemAction.DIGITAL_ASSISTANT,

    loudSoundReductionEnabled = true,

    isPremium = true,
    vendorIdHook = true,

    dynamicEndOfCharge = true,

    connectionSuccessful = true,

    customEq = CustomEq(state = 2, low = 65, mid = 50, high = 70),

    controlStates = mapOf(
        ControlCommandIdentifiers.MIC_MODE to byteArrayOf(0x00),
        ControlCommandIdentifiers.LISTENING_MODE_CONFIGS to byteArrayOf(0x0F),
        ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.STEM_CONFIG to byteArrayOf(0x00),
        ControlCommandIdentifiers.CLICK_HOLD_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG to byteArrayOf(0x00, 0x03),
        ControlCommandIdentifiers.CHIME_VOLUME to byteArrayOf(0x46, 0x50),
        ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_AID to byteArrayOf(0x01, 0x02),
        ControlCommandIdentifiers.HPS_GAIN_SWIPE to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_ASSIST_CONFIG to byteArrayOf(0x02),
        ControlCommandIdentifiers.HRM_STATE to byteArrayOf(0x01),
        ControlCommandIdentifiers.AUTO_ANC_STRENGTH to byteArrayOf(0x45),
        ControlCommandIdentifiers.ONE_BUD_ANC_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_TOGGLE_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_CAP_LEVEL_CONFIG to byteArrayOf(0x52),
        ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE to byteArrayOf(0x01),
        ControlCommandIdentifiers.ALLOW_OFF_OPTION to byteArrayOf(0x01),
        ControlCommandIdentifiers.EAR_DETECTION_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.LISTENING_MODE to byteArrayOf(0x04)
    )
)
}

val demoState = createDemoState()

class AirPodsViewModel(

) : ViewModel() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var appContext: Context
    private lateinit var service: AirPodsService
    private lateinit var controlRepo: ControlCommandRepository
    private val microphoneCaptureLock = Any()
    private val microphoneCapture = ByteArrayOutputStream()
    private var microphoneCaptureUntilMillis = 0L
    private val microphonePreviewPlayer = PcmPreviewPlayer()

    var isReady by mutableStateOf(false)
        private set

    fun init(service: AirPodsService, controlRepo: ControlCommandRepository, sharedPreferences: SharedPreferences, appContext: Context) {
        this.service = service
        this.controlRepo = controlRepo
        this.sharedPreferences = sharedPreferences
        this.appContext = appContext

        observeBroadcasts()
        loadName()
        loadInstance()
        loadSharedPreferences()
        observeAACP()
        observeHeartRate()
        observeNearbyAirPods()
        loadCurrentStatus()
        loadEq()
        loadATT()
        observeATT()
        observeSharedPreferences()
        observeBilling()
        if (isDemoMode) activateDemoMode()
        isReady = true
    }

    private val _uiState = MutableStateFlow(AirPodsUiState())

    val uiState: StateFlow<AirPodsUiState> = _uiState

    private var isDemoMode = false
    private var demoTick = 0

    private val listeners =
        mutableMapOf<ControlCommandIdentifiers, AACPManager.ControlCommandListener>()

    private val xposedRemotePref = XposedRemotePrefProvider.create()

    private lateinit var broadcastReceiver: BroadcastReceiver

    /** Starts the Demo app without creating, binding, or querying any Bluetooth-backed service. */
    fun initDemo(sharedPreferences: SharedPreferences, appContext: Context) {
        if (isReady) return
        this.sharedPreferences = sharedPreferences
        this.appContext = appContext.applicationContext
        isDemoMode = true
        _uiState.value = createDemoState()
        isReady = true
        viewModelScope.launch {
            val pattern = intArrayOf(132, 136, 141, 147, 153, 149, 143, 138)
            while (true) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                val bpm = pattern[demoTick % pattern.size]
                demoTick += 1
                _uiState.update { current ->
                    val nextSample = HeartRateSample(
                        bpm = bpm,
                        sequence = demoTick,
                        receivedAtMillis = now,
                        receivedAtElapsedRealtime = 0L,
                    )
                    current.copy(
                        heartRate = current.heartRate.copy(
                            samples = (current.heartRate.samples + nextSample).takeLast(60)
                        ),
                        nearbyAirPods = current.nearbyAirPods?.copy(lastSeen = now),
                        highResolutionMic = if (current.highResolutionMic.active) {
                            current.highResolutionMic.copy(peak = 0.25f + (demoTick % 6) * 0.1f)
                        } else current.highResolutionMic,
                        heartRateSessions = current.heartRateSessions.copy(
                            current = current.heartRateSessions.current?.let { session ->
                                session.copy(
                                    samples = (session.samples + RecordedHeartRateSample(bpm, now))
                                        .takeLast(3_600)
                                )
                            }
                        ),
                    )
                }
            }
        }
    }

//    private val _cameraAction = MutableStateFlow(
//        sharedPreferences.getString("camera_action", null)
//            ?.let { value -> AACPManager.Companion.StemPressType.entries.find { it.name == value } })
//
//    val cameraAction: StateFlow<AACPManager.Companion.StemPressType?> = _cameraAction
//
//    fun setCameraAction(action: AACPManager.Companion.StemPressType?) {
//        sharedPreferences.edit {
//            if (action == null) remove("camera_action")
//            else putString("camera_action", action.name)
//        }
//        _cameraAction.value = action
//    }

    fun setCustomEq(low: Int, mid: Int, high: Int) {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        val updatedEq = _uiState.value.customEq.copy(low = low, mid = mid, high = high)
        if (!isDemoMode) service.aacpManager.sendCustomEqPacket(updatedEq)
        _uiState.update {
            it.copy(
                customEq = updatedEq
            )
        }
    }

    fun setCustomEqEnabled(enabled: Boolean) {
        if (!isDemoMode) {
            service.aacpManager.sendCustomEqPacket(_uiState.value.customEq.copy(state = if (enabled) 2 else 1))
        }
        _uiState.update {
            it.copy(
                customEq = it.customEq.copy(state = if (enabled) 2 else 1)
            )
        }
    }

    override fun onCleared() {
        if (::controlRepo.isInitialized) {
            listeners.forEach { (id, listener) ->
                controlRepo.remove(id, listener)
            }
        }
        if (::service.isInitialized) {
            service.aacpManager.customEqCallback = null
            service.stopHighResolutionMicrophone()
        }
        microphonePreviewPlayer.close()
        if (::appContext.isInitialized && ::broadcastReceiver.isInitialized) {
            appContext.unregisterReceiver(broadcastReceiver)
        }
    }

    private fun loadName() {
        val name = sharedPreferences.getString("name", "AirPods Pro")!!
        _uiState.update { it.copy(deviceName = name) }
    }

    private fun observeBilling() {
        if (isDemoMode) return
        viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                if (premium) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        if (BuildConfig.PLAY_BUILD) remove("foss_upgraded")
                    }
                    _uiState.update { it.copy(isPremium = true, timeUntilFOSSPremiumExpiry = 0L) }
                } else {
                    if (_uiState.value.timeUntilFOSSPremiumExpiry <= 0L) {
                        setControlCommandBoolean(
                            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                            false
                        )
                        setHeadGesturesEnabled(false)
                        _uiState.update { it.copy(isPremium = false) }
                    }
                }
            }
        }
    }

    private fun observeSharedPreferences() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "name" -> loadName()
                "off_listening_mode", "automatic_ear_detection", "automatic_connection_ctrl_cmd",
                "head_gestures", "left_long_press_action", "right_long_press_action",
                "dynamic_end_of_charge", "foss_upgraded", "premium_expiry_time",
                "spatial_audio_mode" -> loadSharedPreferences()
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun observeBroadcasts() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isDemoMode) when (action) {
                    AirPodsNotifications.AIRPODS_L2CAP_CONNECTED -> {
                        _uiState.update {
                            it.copy(isLocallyConnected = true)
                        }
                    }

                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        _uiState.update {
                            it.copy(isLocallyConnected = false)
                        }
                    }

                    AirPodsNotifications.BATTERY_DATA -> {
                        _uiState.update {
                            it.copy(battery = service.getBattery())
                        }
                    }

                    AirPodsNotifications.EQ_DATA -> {
                        val data = intent.getFloatArrayExtra("eqData") ?: floatArrayOf()

                        _uiState.update {
                            it.copy(eqData = data)
                        }
                    }

                    AirPodsNotifications.AIRPODS_INFORMATION_UPDATED -> {
                        loadInstance()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.BATTERY_DATA)
            addAction(AirPodsNotifications.EQ_DATA)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
        }

        appContext.registerReceiver(
            broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers, value: ByteArray
    ) {
        if (!isDemoMode) controlRepo.setValue(identifier, value)
        _uiState.update {
            it.copy(
                controlStates = it.controlStates + (identifier to value)
            )
        }
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers, enabled: Boolean
    ) {
        setControlCommandValue(
            identifier, if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers, value: Int
    ) {
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers, value: Byte
    ) {
        setControlCommandValue(identifier, byteArrayOf(value))
    }

    fun observeControl(identifier: ControlCommandIdentifiers) {
        val listener = controlRepo.observe(identifier) { value ->
            _uiState.update { state ->
                val current = state.controlStates[identifier]
                if (current?.contentEquals(value) == true) return@update state

                if (identifier == ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE) {
                    state.copy(
                        dynamicEndOfCharge = value[0] == 0x01.toByte(),
                        controlStates = state.controlStates + (identifier to value)
                    )
                } else {
                    state.copy(
                        controlStates = state.controlStates + (identifier to value)
                    )
                }
            }
        }

        listeners[identifier] = listener
    }

    // I'm lazy, sorry.
    fun observeAACP() {
        val identifiersList = listOf(
            ControlCommandIdentifiers.MIC_MODE,
            ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
            ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
            ControlCommandIdentifiers.LISTENING_MODE_CONFIGS,
            ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
            ControlCommandIdentifiers.LISTENING_MODE,
            ControlCommandIdentifiers.AUTO_ANSWER_MODE,
            ControlCommandIdentifiers.CHIME_VOLUME,
            ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
            ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
            ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
            ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifiers.HEARING_AID,
            ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            ControlCommandIdentifiers.HPS_GAIN_SWIPE,
            ControlCommandIdentifiers.HEARING_ASSIST_CONFIG,
            ControlCommandIdentifiers.ALLOW_OFF_OPTION,
            ControlCommandIdentifiers.STEM_CONFIG,
            ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
            ControlCommandIdentifiers.ALLOW_AUTO_CONNECT,
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            ControlCommandIdentifiers.OWNS_CONNECTION,
            ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
            ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE
        )
        for (identifier in identifiersList) {
            observeControl(identifier)
        }
        service.aacpManager.customEqCallback = { customEq ->
            _uiState.update { it.copy(customEq = customEq) }
        }
    }

    private fun observeHeartRate() {
        viewModelScope.launch {
            combine(
                service.heartRateState,
                service.heartRateSessionState,
                service.healthConnectState,
            ) { heartRate, sessions, export -> Triple(heartRate, sessions, export) }
                .collect { (heartRate, sessions, export) ->
                    _uiState.update {
                        it.copy(
                            heartRate = heartRate,
                            heartRateSessions = sessions,
                            healthConnect = export,
                        )
                    }
                }
        }
    }

    private fun observeNearbyAirPods() {
        viewModelScope.launch {
            service.nearbyAirPodsState.collect { nearby ->
                _uiState.update { it.copy(nearbyAirPods = nearby) }
            }
        }
    }

    fun loadCurrentStatus() {
        if (isDemoMode) return
        service.let { service ->
            _uiState.update {
                it.copy(
                    isLocallyConnected = service.isAacpTransportHealthy(),
                    heartRate = service.heartRateState.value,
                    heartRateSessions = service.heartRateSessionState.value,
                    healthConnect = service.healthConnectState.value,
                    battery = service.getBattery(),
                    ancMode = controlRepo.getValue(ControlCommandIdentifiers.LISTENING_MODE)?.get(0)?.toInt() ?: 1,
                    controlStates = controlRepo.getMap()
                )
            }
        }
    }

    private fun loadSharedPreferences() {
        val offListeningModeEnabled = sharedPreferences.getBoolean("off_listening_mode", true)
        val automaticEarDetectionEnabled =
            sharedPreferences.getBoolean("automatic_ear_detection", true)
        val automaticConnectionEnabled =
            sharedPreferences.getBoolean("automatic_connection_ctrl_cmd", true)
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures", true)
        val spatialAudioMode = runCatching {
            SpatialAudioMode.valueOf(
                sharedPreferences.getString("spatial_audio_mode", SpatialAudioMode.OFF.name)
                    ?: SpatialAudioMode.OFF.name
            )
        }.getOrDefault(SpatialAudioMode.OFF)
        val leftAction = StemAction.valueOf(
            sharedPreferences.getString(
                "left_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val rightAction = StemAction.valueOf(
            sharedPreferences.getString(
                "right_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false)
        val dynamicEndOfCharge = sharedPreferences.getBoolean("dynamic_end_of_charge", false)

        val connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false)

        _uiState.update {
            it.copy(
                offListeningMode = offListeningModeEnabled,
                automaticEarDetectionEnabled = automaticEarDetectionEnabled,
                automaticConnectionEnabled = automaticConnectionEnabled,
                headGesturesEnabled = headGesturesEnabled,
                spatialAudioMode = spatialAudioMode,
                leftAction = leftAction,
                rightAction = rightAction,
                vendorIdHook = vendorIdHook,
                dynamicEndOfCharge = dynamicEndOfCharge,
                connectionSuccessful = connectionSuccessful,
            )
        }

        // faulty update on Play caused PLAY_BUILD to be false and resulted in use of FOSS billing in Play. since FOSS is not verified, we need to give 2 weeks to verify the purchase
        if (BuildConfig.PLAY_BUILD) {
            val fossUpgraded = sharedPreferences.getBoolean("foss_upgraded", false)
            val expiryTime = sharedPreferences.getLong("premium_expiry_time", 0L)
            val now = System.currentTimeMillis()

            when {
                // existing temporary premium
                expiryTime > 0L -> {
                    if (expiryTime <= now) {
                        sharedPreferences.edit {
                            remove("premium_expiry_time")
                            remove("foss_upgraded")
                        }

                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = 0L,
                                isPremium = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = expiryTime - now,
                                isPremium = true
                            )
                        }
                    }
                }

                // First migration from accidental FOSS Play build
                fossUpgraded && !_uiState.value.isPremium -> {
                    val newExpiry = now + 28L * 24 * 60 * 60 * 1000

                    sharedPreferences.edit {
                        putLong("premium_expiry_time", newExpiry)
                    }

                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = newExpiry - now,
                            isPremium = true
                        )
                    }
                }
            }
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("off_listening_mode", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.ALLOW_OFF_OPTION, enabled)
        _uiState.update {
            it.copy(offListeningMode = enabled)
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures", enabled) }
        _uiState.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setDynamicEndOfCharge(enabled: Boolean) {
        if (!isDemoMode) {
            service.aacpManager.sendControlCommand(ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE.value, enabled)
        }
        sharedPreferences.edit { putBoolean("dynamic_end_of_charge", enabled) }
        _uiState.update {
            it.copy(dynamicEndOfCharge = enabled)
        }
    }

    private fun loadEq() {
        _uiState.update {
            it.copy(
                customEq = service.aacpManager.customEq
            )
        }
    }

    private fun loadInstance() {
        val instance = service.airpodsInstance ?: AirPodsInstance(
            name = "AirPods",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            serialNumber = null,
            leftSerialNumber = null,
            rightSerialNumber = null,
            version1 = null,
            version2 = null,
            version3 = null,
        )

        _uiState.update {
            it.copy(
                capabilities = instance.model.capabilities,
                instance = instance,
                modelName = instance.model.displayName,
                actualModel = instance.actualModelNumber,
                serialNumbers = listOf(
                    instance.serialNumber ?: "",
                    instance.leftSerialNumber ?: "",
                    instance.rightSerialNumber ?: ""
                ),
                version1 = instance.version1 ?: "",
                version2 = instance.version2 ?: "",
                version3 = instance.version3 ?: ""
            )
        }
    }

    fun reconnectFromSavedMac() {
        if (isDemoMode) {
            _uiState.update { it.copy(isLocallyConnected = true) }
            return
        }
        if (!::service.isInitialized) return
        service.reconnectFromSavedMac()
    }

    fun setName(name: String) {
        if (isDemoMode) {
            sharedPreferences.edit { putString("name", name) }
            _uiState.update { it.copy(deviceName = name) }
        } else {
            service.setName(name)
        }
    }

    fun startHeadTracking() {
        if (!isDemoMode) service.startHeadTracking()
        _uiState.update { it.copy(headTrackingActive = true) }
    }

    fun stopHeadTracking() {
        if (!isDemoMode) service.stopHeadTracking()
        _uiState.update { it.copy(headTrackingActive = false) }
    }

    fun setSpatialAudioMode(mode: SpatialAudioMode) {
        sharedPreferences.edit { putString("spatial_audio_mode", mode.name) }
        _uiState.update { it.copy(spatialAudioMode = mode) }
    }

    fun startHighResolutionMicrophone() {
        if (!isReady || _uiState.value.highResolutionMic.starting ||
            _uiState.value.highResolutionMic.active
        ) return
        if (isDemoMode) {
            _uiState.update {
                it.copy(
                    highResolutionMic = it.highResolutionMic.copy(
                        active = true,
                        peak = 0.42f,
                        sampleRate = 24_000,
                        channelCount = 1,
                        error = null,
                    )
                )
            }
            return
        }
        _uiState.update {
            it.copy(highResolutionMic = it.highResolutionMic.copy(starting = true, error = null))
        }
        viewModelScope.launch {
            val started = service.startHighResolutionMicrophone(object : AacEldDecoder.Listener {
                override fun onPcmData(data: ByteArray, sampleRate: Int, channelCount: Int) {
                    val peak = pcmPeak(data)
                    val (completedClip, stillRecording) = synchronized(microphoneCaptureLock) {
                        if (microphoneCaptureUntilMillis == 0L) return@synchronized null to false
                        val remaining = (MAX_MIC_CAPTURE_BYTES - microphoneCapture.size()).coerceAtLeast(0)
                        microphoneCapture.write(data, 0, minOf(data.size, remaining))
                        val completed = if (
                            System.currentTimeMillis() < microphoneCaptureUntilMillis && remaining > data.size
                        ) {
                            null
                        } else {
                            PcmClip(microphoneCapture.toByteArray(), sampleRate, channelCount).also {
                                microphoneCapture.reset()
                                microphoneCaptureUntilMillis = 0L
                            }
                        }
                        completed to (microphoneCaptureUntilMillis != 0L)
                    }
                    _uiState.update { current ->
                        current.copy(
                            highResolutionMic = current.highResolutionMic.copy(
                                active = true,
                                starting = false,
                                peak = peak,
                                sampleRate = sampleRate,
                                channelCount = channelCount,
                                recording = stillRecording,
                                recordedClip = completedClip ?: current.highResolutionMic.recordedClip,
                            )
                        )
                    }
                }

                override fun onDecoderError(message: String, cause: Throwable?) {
                    service.stopHighResolutionMicrophone()
                    FeatureDiagnostics.event(appContext, "mic_decoder_error")
                    _uiState.update {
                        it.copy(
                            highResolutionMic = it.highResolutionMic.copy(
                                starting = false,
                                active = false,
                                recording = false,
                                error = message,
                            )
                        )
                    }
                }
            })
            if (!started) {
                FeatureDiagnostics.event(appContext, "mic_start_failed")
                _uiState.update {
                    it.copy(highResolutionMic = it.highResolutionMic.copy(starting = false))
                }
            }
        }
    }

    fun stopHighResolutionMicrophone() {
        if (!isReady) return
        if (isDemoMode) {
            _uiState.update {
                it.copy(highResolutionMic = it.highResolutionMic.copy(active = false, recording = false, peak = 0f))
            }
            return
        }
        service.stopHighResolutionMicrophone()
        synchronized(microphoneCaptureLock) {
            microphoneCapture.reset()
            microphoneCaptureUntilMillis = 0L
        }
        _uiState.update {
            it.copy(highResolutionMic = it.highResolutionMic.copy(active = false, recording = false, peak = 0f))
        }
    }

    fun recordHighResolutionMicrophoneSample() {
        if (!_uiState.value.highResolutionMic.active) return
        if (isDemoMode) {
            _uiState.update {
                it.copy(
                    highResolutionMic = it.highResolutionMic.copy(
                        recording = false,
                        recordedClip = PcmClip(ByteArray(4_800), 24_000, 1),
                    )
                )
            }
            return
        }
        synchronized(microphoneCaptureLock) {
            microphoneCapture.reset()
            microphoneCaptureUntilMillis = System.currentTimeMillis() + MIC_CAPTURE_DURATION_MILLIS
        }
        _uiState.update {
            it.copy(highResolutionMic = it.highResolutionMic.copy(recording = true, recordedClip = null))
        }
    }

    fun playHighResolutionMicrophoneSample() {
        if (isDemoMode) return
        _uiState.value.highResolutionMic.recordedClip?.let(microphonePreviewPlayer::play)
    }

    private fun pcmPeak(data: ByteArray): Float {
        if (data.size < 2) return 0f
        val samples = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.get().toInt()))
        return (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
    }

    private companion object {
        const val MIC_CAPTURE_DURATION_MILLIS = 5_000L
        const val MAX_MIC_CAPTURE_BYTES = 1_000_000
    }

    fun setHeartRateMonitoringEnabled(enabled: Boolean) {
        if (!isReady) return
        if (isDemoMode) {
            _uiState.update {
                it.copy(
                    heartRate = it.heartRate.copy(
                        enabled = enabled,
                        status = when {
                            !enabled -> HeartRateMonitoringStatus.OFF
                            it.isLocallyConnected -> HeartRateMonitoringStatus.LIVE
                            else -> HeartRateMonitoringStatus.WAITING_FOR_AIRPODS
                        }
                    )
                )
            }
            return
        }
        service.setHeartRateMonitoringEnabled(enabled)
    }

    fun reconnectAacpForHeartRate() {
        if (!isReady || isDemoMode) return
        service.reconnectAacpForHeartRate()
    }

    fun startNewHeartRateSession() {
        if (!isReady) return
        if (isDemoMode) {
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    heartRateSessions = it.heartRateSessions.copy(
                        current = HeartRateSession(id = now, startedAtMillis = now),
                        recordingPaused = false,
                    )
                )
            }
            return
        }
        service.startNewHeartRateSession()
    }

    fun finishHeartRateWorkout() {
        if (!isReady) return
        if (isDemoMode) {
            _uiState.update { state ->
                state.copy(
                    heartRateSessions = state.heartRateSessions.copy(
                        current = state.heartRateSessions.current?.copy(
                            workoutEndedAtMillis = System.currentTimeMillis()
                        )
                    )
                )
            }
            return
        }
        service.finishHeartRateWorkout()
    }

    fun setHeartRateZoneConfig(upperBounds: List<Int>?) {
        if (!isReady) return
        if (isDemoMode) {
            _uiState.update {
                it.copy(
                    heartRateSessions = it.heartRateSessions.copy(
                        zoneConfig = upperBounds?.let(::HeartRateZoneConfig)
                    )
                )
            }
            return
        }
        service.setHeartRateZoneConfig(upperBounds?.let(::HeartRateZoneConfig))
    }

    fun refreshHealthConnectExportState() {
        if (!isReady || isDemoMode) return
        service.refreshHealthConnectExportState()
    }

    fun setHealthConnectExportEnabled(enabled: Boolean) {
        if (!isReady) return
        if (isDemoMode) {
            _uiState.update {
                it.copy(
                    healthConnect = it.healthConnect.copy(
                        enabled = enabled,
                        status = if (enabled) {
                            HealthConnectExportStatus.ENABLED
                        } else {
                            HealthConnectExportStatus.READY
                        }
                    )
                )
            }
            return
        }
        service.setHealthConnectExportEnabled(enabled)
    }

    fun setHealthConnectDetailedSamples(detailed: Boolean) {
        if (!isReady) return
        if (isDemoMode) {
            _uiState.update {
                it.copy(
                    healthConnect = it.healthConnect.copy(detailedSamples = detailed)
                )
            }
            return
        }
        service.setHealthConnectDetailedSamples(detailed)
    }

    fun markHealthConnectPermissionDenied() {
        if (!isReady || isDemoMode) return
        service.markHealthConnectPermissionDenied()
    }

    fun setATTCharacteristicValue(handle: ATTHandles, value: ByteArray) {
        when (handle) {
            // ideally should be using a different viewmodel for ATT based things because there are a lot of values, and I am not going to add all to this state, but there's loudsoundreduction.
            ATTHandles.LOUD_SOUND_REDUCTION -> {
                _uiState.value = _uiState.value.copy(loudSoundReductionEnabled = value[0].toInt() == 0x01)
            }
            ATTHandles.HEARING_AID -> {
                _uiState.value = _uiState.value.copy(hearingAidData = value)
            }
            ATTHandles.TRANSPARENCY -> {
                _uiState.value = _uiState.value.copy(transparencyData = value)
            }
        }
        if (isDemoMode) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                service.attManager.writeCharacteristic(handle, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadATT() {
        val loudSoundReduction = service.attManager.getCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION) ?: byteArrayOf()
        val loudSoundReductionEnabled = if (loudSoundReduction.isNotEmpty()) {
            loudSoundReduction[0].toInt() == 1
        } else false
        val hearingAidData = service.attManager.getCharacteristic(ATTHandles.HEARING_AID) ?: byteArrayOf()
        val transparencyData = service.attManager.getCharacteristic(ATTHandles.TRANSPARENCY) ?: byteArrayOf()
        _uiState.update {
            it.copy(
                loudSoundReductionEnabled = loudSoundReductionEnabled,
                transparencyData = transparencyData,
                hearingAidData = hearingAidData
            )
        }
    }

    fun observeATT() {
        viewModelScope.launch(Dispatchers.IO) {
            service.attManager.enableNotification(ATTCCCDHandles.HEARING_AID)
            service.attManager.enableNotification(ATTCCCDHandles.TRANSPARENCY)
        }
        service.attManager.setOnNotificationReceived { handle, value ->
            when (handle) {
                ATTHandles.LOUD_SOUND_REDUCTION.value.toByte() -> {
                    val loudSoundReductionEnabled = if (value.isNotEmpty()) {
                        value[0].toInt() == 1
                    } else false
                    _uiState.update {
                        it.copy(loudSoundReductionEnabled = loudSoundReductionEnabled)
                    }
                }
                ATTHandles.HEARING_AID.value.toByte() -> {
                    _uiState.update {
                        it.copy(hearingAidData = value)
                    }
                }
                ATTHandles.TRANSPARENCY.value.toByte() -> {
                    _uiState.update {
                        it.copy(transparencyData = value)
                    }
                }
            }
        }
    }

    fun setAutomaticEarDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.EAR_DETECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticEarDetectionEnabled = enabled
            )
        }
    }

    fun setAutomaticConnectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_connection_ctrl_cmd", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticConnectionEnabled = enabled
            )
        }
    }

    fun activateDemoMode() {
        isDemoMode = true
        _uiState.update { createDemoState() }
        isReady = true
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phoneByte: Byte, mediaByte: Byte) {
        if (isDemoMode) {
            _uiState.update { it.copy(eqData = eq.copyOf()) }
        } else {
            service.aacpManager.sendPhoneMediaEQ(eq, phoneByte, mediaByte)
        }
    }

    fun setLongPressAction(side: String, action: StemAction) {
        val prefKey = if (side.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
        _uiState.update {
            if (side.lowercase() == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
        }
    }

    private fun countEnabledModes(byteValue: Int): Int {
        var count = 0
        if ((byteValue and 0x01) != 0) count++
        if ((byteValue and 0x02) != 0) count++
        if ((byteValue and 0x04) != 0) count++
        if ((byteValue and 0x08) != 0) count++
        return count
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = uiState.value.controlStates[ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommandByte(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS, newValue.toByte())
        sharedPreferences.edit { putInt("long_press_byte", newValue) }
    }

    fun disconnect() {
        if (isDemoMode) {
            _uiState.update {
                it.copy(isLocallyConnected = false)
            }
        } else {
            service.disconnectAirPods()
            if (appContext.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    appContext, "App has disconnected, disconnect from Android Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
