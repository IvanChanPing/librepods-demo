package me.kavishdevar.librepods.findmy

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.edit
import com.openbubbles.findmy.bridge.IFindMyBridge
import com.openbubbles.findmy.bridge.IFindMyBridgeCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.utils.FeatureDiagnostics
import org.json.JSONArray
import org.json.JSONObject

internal enum class FindMyProviderStatus {
    PROVIDER_MISSING,
    UNPAIRED,
    CONNECTING,
    PROVIDER_NOT_RUNNING,
    ACCOUNT_REQUIRED,
    KEYCHAIN_REQUIRED,
    FIND_MY_SETUP_REQUIRED,
    READY,
    SYNC_FAILED,
    ERROR,
}

internal data class FindMyNetworkLocation(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Int?,
    val status: Int?,
    val confidence: Int?,
    val timestampMs: Long,
)

internal data class FindMyNetworkDevice(
    val opaqueId: String,
    val name: String,
    val emoji: String,
    val productId: Long,
    val batteryLevel: Long?,
    val vendorId: Long,
    val model: String,
    val systemVersion: String,
    val shared: Boolean,
    val location: FindMyNetworkLocation?,
)

internal data class OpenBubblesFindMyState(
    val status: FindMyProviderStatus,
    val devices: List<FindMyNetworkDevice> = emptyList(),
    val refreshedAtMs: Long = 0,
    val usingCache: Boolean = false,
    val errorCode: String? = null,
)

/**
 * Lifecycle-owned client for the user-approved OpenBubbles Find My provider.
 *
 * The capability token and cached locations live only in the find_my private
 * preferences file, which is excluded from both cloud backup and device transfer.
 */
internal class OpenBubblesFindMyClient(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cached = readCache()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OpenBubblesFindMyState> = _state.asStateFlow()

    private var bridge: IFindMyBridge? = null
    private var bindRegistered = false
    private var pendingOperation: Operation? = null
    private var pendingToken: String? = null
    private var requestGeneration = 0
    private var timeout: Runnable? = null

    private enum class Operation { STATUS, REFRESH, REVOKE }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bridge = IFindMyBridge.Stub.asInterface(binder)
            executePending()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mainHandler.post {
                failCurrent("service_disconnected")
            }
        }

        override fun onBindingDied(name: ComponentName) {
            mainHandler.post {
                failCurrent("binding_died")
            }
        }

        override fun onNullBinding(name: ComponentName) {
            mainHandler.post {
                failCurrent("null_binding")
            }
        }
    }

    fun start() {
        when {
            !providerInstalled() -> updateStatus(FindMyProviderStatus.PROVIDER_MISSING)
            token() == null -> updateStatus(FindMyProviderStatus.UNPAIRED)
            else -> request(Operation.STATUS, token()!!)
        }
    }

    fun refresh() {
        val token = token()
        if (token == null) {
            updateStatus(FindMyProviderStatus.UNPAIRED)
            return
        }
        request(Operation.REFRESH, token)
    }

    fun pairingIntent(): Intent? {
        if (!providerInstalled()) return null
        return Intent().setComponent(ComponentName(PROVIDER_PACKAGE, PAIRING_ACTIVITY))
    }

    fun handlePairingResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK ||
            data?.getIntExtra(EXTRA_SCHEMA_VERSION, 0) != SCHEMA_VERSION
        ) {
            FeatureDiagnostics.event(appContext, "find_my_pairing_cancelled")
            if (token() == null) updateStatus(FindMyProviderStatus.UNPAIRED)
            return
        }
        val receivedToken = data.getStringExtra(EXTRA_TOKEN)
            ?.takeIf { it.length in 16..256 }
        if (receivedToken == null) {
            FeatureDiagnostics.event(appContext, "find_my_pairing_invalid")
            updateError("pairing_invalid")
            return
        }
        preferences.edit { putString(KEY_TOKEN, receivedToken) }
        FeatureDiagnostics.event(appContext, "find_my_pairing_completed")
        request(Operation.STATUS, receivedToken)
    }

    fun openProvider(findMyPage: Boolean): Boolean {
        val intent = if (findMyPage) {
            Intent(Intent.ACTION_SEND)
                .setComponent(ComponentName(PROVIDER_PACKAGE, MAIN_ACTIVITY))
                .setType("text/plain")
                .putExtra(FIND_MY_SHORTCUT_EXTRA, FIND_MY_SHORTCUT)
        } else {
            appContext.packageManager.getLaunchIntentForPackage(PROVIDER_PACKAGE)
        } ?: return false
        return runCatching {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            FeatureDiagnostics.event(appContext, "find_my_provider_opened")
            true
        }.getOrDefault(false)
    }

    fun disconnect() {
        val oldToken = token()
        clearLocal()
        updateStatus(FindMyProviderStatus.UNPAIRED)
        FeatureDiagnostics.event(appContext, "find_my_provider_disconnected")
        if (oldToken != null && providerInstalled()) {
            request(Operation.REVOKE, oldToken)
        }
    }

    fun close() {
        requestGeneration += 1
        timeout?.let(mainHandler::removeCallbacks)
        timeout = null
        pendingOperation = null
        pendingToken = null
        unbind()
    }

    private fun initialState(): OpenBubblesFindMyState {
        return when {
            !providerInstalled() -> OpenBubblesFindMyState(FindMyProviderStatus.PROVIDER_MISSING)
            token() == null -> OpenBubblesFindMyState(FindMyProviderStatus.UNPAIRED)
            else -> OpenBubblesFindMyState(
                status = FindMyProviderStatus.CONNECTING,
                devices = cached.devices,
                refreshedAtMs = cached.refreshedAtMs,
                usingCache = cached.devices.isNotEmpty(),
            )
        }
    }

    private fun request(operation: Operation, requestToken: String) {
        if (!providerInstalled()) {
            if (operation != Operation.REVOKE) {
                updateStatus(FindMyProviderStatus.PROVIDER_MISSING)
            }
            return
        }
        pendingOperation = operation
        pendingToken = requestToken
        if (operation != Operation.REVOKE) {
            _state.value = _state.value.copy(status = FindMyProviderStatus.CONNECTING)
        }
        if (bridge != null) {
            executePending()
            return
        }
        if (bindRegistered) return
        val intent = Intent().setComponent(ComponentName(PROVIDER_PACKAGE, BRIDGE_SERVICE))
        bindRegistered = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bindRegistered) {
            failCurrent("bind_failed")
        }
    }

    private fun executePending() {
        val service = bridge ?: return
        val operation = pendingOperation ?: return
        val requestToken = pendingToken ?: return
        val generation = ++requestGeneration
        timeout?.let(mainHandler::removeCallbacks)
        timeout = Runnable {
            if (generation == requestGeneration) {
                FeatureDiagnostics.event(appContext, "find_my_provider_timeout")
                updateError("timeout")
                finishRequest()
            }
        }.also { runnable ->
            mainHandler.postDelayed(runnable, if (operation == Operation.REFRESH) 45_000 else 15_000)
        }

        if (operation == Operation.REVOKE) {
            runCatching { service.revoke(requestToken) }
            finishRequest()
            return
        }

        val callback = object : IFindMyBridgeCallback.Stub() {
            override fun onResult(result: Bundle) {
                mainHandler.post {
                    if (generation != requestGeneration) return@post
                    handleResult(result, operation)
                    finishRequest()
                }
            }
        }
        runCatching {
            when (operation) {
                Operation.STATUS -> service.getStatus(requestToken, callback)
                Operation.REFRESH -> service.refresh(requestToken, callback)
                Operation.REVOKE -> Unit
            }
        }.onFailure {
            FeatureDiagnostics.event(appContext, "find_my_provider_call_failed")
            if (operation != Operation.REVOKE) updateError("call_failed")
            finishRequest()
        }
    }

    private fun handleResult(result: Bundle, operation: Operation) {
        if (result.getInt(KEY_SCHEMA_VERSION, 0) != SCHEMA_VERSION) {
            updateError("schema_mismatch")
            return
        }
        val wireState = result.getString(KEY_STATE).orEmpty()
        val status = when (wireState) {
            STATE_UNAUTHORIZED -> {
                clearLocal()
                FindMyProviderStatus.UNPAIRED
            }
            STATE_PROVIDER_NOT_RUNNING -> FindMyProviderStatus.PROVIDER_NOT_RUNNING
            STATE_ACCOUNT_REQUIRED -> FindMyProviderStatus.ACCOUNT_REQUIRED
            STATE_KEYCHAIN_REQUIRED -> FindMyProviderStatus.KEYCHAIN_REQUIRED
            STATE_FIND_MY_SETUP_REQUIRED -> FindMyProviderStatus.FIND_MY_SETUP_REQUIRED
            STATE_READY -> FindMyProviderStatus.READY
            STATE_SYNC_FAILED -> FindMyProviderStatus.SYNC_FAILED
            STATE_NATIVE_UNAVAILABLE -> FindMyProviderStatus.ERROR
            else -> FindMyProviderStatus.ERROR
        }

        if (status == FindMyProviderStatus.READY && operation == Operation.STATUS) {
            _state.value = OpenBubblesFindMyState(
                status = FindMyProviderStatus.CONNECTING,
                devices = cached.devices,
                refreshedAtMs = cached.refreshedAtMs,
                usingCache = cached.devices.isNotEmpty(),
            )
            mainHandler.post { refresh() }
            return
        }

        val devices = if (status == FindMyProviderStatus.READY) {
            parseDevices(result)
        } else {
            cached.devices
        }
        val refreshedAtMs = result.getLong(KEY_REFRESHED_AT_MS, cached.refreshedAtMs)
            .takeIf { it >= 0 } ?: cached.refreshedAtMs
        if (status == FindMyProviderStatus.READY) {
            writeCache(devices, refreshedAtMs)
            cached.devices = devices
            cached.refreshedAtMs = refreshedAtMs
        }
        val usingCache = status != FindMyProviderStatus.READY && devices.isNotEmpty()
        _state.value = OpenBubblesFindMyState(
            status = status,
            devices = devices,
            refreshedAtMs = if (usingCache) cached.refreshedAtMs else refreshedAtMs,
            usingCache = usingCache,
            errorCode = result.getString(KEY_ERROR_CODE)?.take(64),
        )
        FeatureDiagnostics.event(appContext, "find_my_provider_${status.name.lowercase()}")
    }

    private fun parseDevices(result: Bundle): List<FindMyNetworkDevice> {
        val bundles = result.getParcelableArrayList(KEY_DEVICES, Bundle::class.java).orEmpty()
        if (bundles.size > MAX_DEVICES) return emptyList()
        return bundles.mapNotNull(::parseDevice)
    }

    private fun parseDevice(bundle: Bundle): FindMyNetworkDevice? {
        val opaqueId = bundle.getString(KEY_OPAQUE_ID)
            ?.takeIf { it.length == 64 && it.all(::isLowerHex) }
            ?: return null
        val name = bundle.getString(KEY_NAME).orEmpty().take(128).ifBlank { "Find My device" }
        val latitude = bundle.getDouble(KEY_LATITUDE)
        val longitude = bundle.getDouble(KEY_LONGITUDE)
        val timestamp = bundle.getLong(KEY_TIMESTAMP_MS)
        val location = if (
            bundle.getBoolean(KEY_HAS_LOCATION) &&
            latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            timestamp >= 0
        ) {
            FindMyNetworkLocation(
                latitude = latitude,
                longitude = longitude,
                horizontalAccuracy = bundle.optionalByteInt(KEY_HORIZONTAL_ACCURACY),
                status = bundle.optionalByteInt(KEY_STATUS),
                confidence = bundle.optionalByteInt(KEY_CONFIDENCE),
                timestampMs = timestamp,
            )
        } else {
            null
        }
        return FindMyNetworkDevice(
            opaqueId = opaqueId,
            name = name,
            emoji = bundle.getString(KEY_EMOJI).orEmpty().take(16),
            productId = bundle.getLong(KEY_PRODUCT_ID),
            batteryLevel = bundle.getLong(KEY_BATTERY_LEVEL)
                .takeIf { bundle.getBoolean(KEY_HAS_BATTERY_LEVEL) },
            vendorId = bundle.getLong(KEY_VENDOR_ID),
            model = bundle.getString(KEY_MODEL).orEmpty().take(128),
            systemVersion = bundle.getString(KEY_SYSTEM_VERSION).orEmpty().take(64),
            shared = bundle.getBoolean(KEY_SHARED),
            location = location,
        )
    }

    private fun Bundle.optionalByteInt(key: String): Int? {
        if (!containsKey(key)) return null
        return getInt(key).takeIf { it in 0..255 }
    }

    private fun failCurrent(code: String) {
        FeatureDiagnostics.event(appContext, "find_my_provider_${code}")
        if (pendingOperation != Operation.REVOKE) updateError(code)
        finishRequest()
    }

    private fun updateError(code: String) {
        _state.value = OpenBubblesFindMyState(
            status = FindMyProviderStatus.ERROR,
            devices = cached.devices,
            refreshedAtMs = cached.refreshedAtMs,
            usingCache = cached.devices.isNotEmpty(),
            errorCode = code.take(64),
        )
    }

    private fun updateStatus(status: FindMyProviderStatus) {
        _state.value = OpenBubblesFindMyState(
            status = status,
            devices = cached.devices,
            refreshedAtMs = cached.refreshedAtMs,
            usingCache = cached.devices.isNotEmpty() && status != FindMyProviderStatus.READY,
        )
    }

    private fun finishRequest() {
        requestGeneration += 1
        timeout?.let(mainHandler::removeCallbacks)
        timeout = null
        pendingOperation = null
        pendingToken = null
        unbind()
    }

    private fun unbind() {
        if (bindRegistered) {
            runCatching { appContext.unbindService(connection) }
        }
        bindRegistered = false
        bridge = null
    }

    @Suppress("DEPRECATION")
    private fun providerInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(PROVIDER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun token(): String? = preferences.getString(KEY_TOKEN, null)
        ?.takeIf { it.length in 16..256 }

    private fun clearLocal() {
        preferences.edit {
            remove(KEY_TOKEN)
            remove(KEY_CACHE)
        }
        cached.devices = emptyList()
        cached.refreshedAtMs = 0
    }

    private data class MutableCache(
        var devices: List<FindMyNetworkDevice>,
        var refreshedAtMs: Long,
    )

    private fun readCache(): MutableCache {
        val root = preferences.getString(KEY_CACHE, null)
            ?.let { encoded -> runCatching { JSONObject(encoded) }.getOrNull() }
            ?: return MutableCache(emptyList(), 0)
        if (root.optInt(KEY_SCHEMA_VERSION) != SCHEMA_VERSION) {
            return MutableCache(emptyList(), 0)
        }
        val devicesJson = root.optJSONArray(KEY_DEVICES) ?: JSONArray()
        val devices = buildList {
            for (index in 0 until minOf(devicesJson.length(), MAX_DEVICES)) {
                parseCachedDevice(devicesJson.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
        return MutableCache(devices, root.optLong(KEY_REFRESHED_AT_MS).coerceAtLeast(0))
    }

    private fun parseCachedDevice(json: JSONObject): FindMyNetworkDevice? {
        val opaqueId = json.optString(KEY_OPAQUE_ID)
            .takeIf { it.length == 64 && it.all(::isLowerHex) }
            ?: return null
        val location = json.optJSONObject("location")?.let { value ->
            val latitude = value.optDouble(KEY_LATITUDE, Double.NaN)
            val longitude = value.optDouble(KEY_LONGITUDE, Double.NaN)
            val timestamp = value.optLong(KEY_TIMESTAMP_MS, -1)
            if (
                latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                timestamp >= 0
            ) {
                FindMyNetworkLocation(
                    latitude,
                    longitude,
                    value.optInt(KEY_HORIZONTAL_ACCURACY)
                        .takeIf { value.has(KEY_HORIZONTAL_ACCURACY) && it in 0..255 },
                    value.optInt(KEY_STATUS)
                        .takeIf { value.has(KEY_STATUS) && it in 0..255 },
                    value.optInt(KEY_CONFIDENCE)
                        .takeIf { value.has(KEY_CONFIDENCE) && it in 0..255 },
                    timestamp,
                )
            } else {
                null
            }
        }
        return FindMyNetworkDevice(
            opaqueId = opaqueId,
            name = json.optString(KEY_NAME).take(128).ifBlank { "Find My device" },
            emoji = json.optString(KEY_EMOJI).take(16),
            productId = json.optLong(KEY_PRODUCT_ID),
            batteryLevel = json.optLong(KEY_BATTERY_LEVEL).takeIf { json.has(KEY_BATTERY_LEVEL) },
            vendorId = json.optLong(KEY_VENDOR_ID),
            model = json.optString(KEY_MODEL).take(128),
            systemVersion = json.optString(KEY_SYSTEM_VERSION).take(64),
            shared = json.optBoolean(KEY_SHARED),
            location = location,
        )
    }

    private fun writeCache(devices: List<FindMyNetworkDevice>, refreshedAtMs: Long) {
        val devicesJson = JSONArray()
        devices.take(MAX_DEVICES).forEach { device ->
            devicesJson.put(JSONObject().apply {
                put(KEY_OPAQUE_ID, device.opaqueId)
                put(KEY_NAME, device.name)
                put(KEY_EMOJI, device.emoji)
                put(KEY_PRODUCT_ID, device.productId)
                device.batteryLevel?.let { put(KEY_BATTERY_LEVEL, it) }
                put(KEY_VENDOR_ID, device.vendorId)
                put(KEY_MODEL, device.model)
                put(KEY_SYSTEM_VERSION, device.systemVersion)
                put(KEY_SHARED, device.shared)
                device.location?.let { location ->
                    put("location", JSONObject().apply {
                        put(KEY_LATITUDE, location.latitude)
                        put(KEY_LONGITUDE, location.longitude)
                        location.horizontalAccuracy?.let { put(KEY_HORIZONTAL_ACCURACY, it) }
                        location.status?.let { put(KEY_STATUS, it) }
                        location.confidence?.let { put(KEY_CONFIDENCE, it) }
                        put(KEY_TIMESTAMP_MS, location.timestampMs)
                    })
                }
            })
        }
        val root = JSONObject()
            .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .put(KEY_REFRESHED_AT_MS, refreshedAtMs)
            .put(KEY_DEVICES, devicesJson)
        preferences.edit { putString(KEY_CACHE, root.toString()) }
    }

    private fun isLowerHex(character: Char): Boolean =
        character in '0'..'9' || character in 'a'..'f'

    private companion object {
        const val PROVIDER_PACKAGE = "com.openbubbles.messaging"
        const val MAIN_ACTIVITY = "com.bluebubbles.messaging.MainActivity"
        const val PAIRING_ACTIVITY =
            "com.bluebubbles.messaging.services.findmy.FindMyPairingActivity"
        const val BRIDGE_SERVICE =
            "com.bluebubbles.messaging.services.findmy.FindMyBridgeService"
        const val FIND_MY_SHORTCUT_EXTRA = "android.intent.extra.shortcut.ID"
        const val FIND_MY_SHORTCUT = "-54"
        const val PREFERENCES = "find_my"
        const val KEY_TOKEN = "openbubbles_token"
        const val KEY_CACHE = "openbubbles_cache"
        const val MAX_DEVICES = 128
        const val SCHEMA_VERSION = 1
        const val EXTRA_SCHEMA_VERSION = "com.openbubbles.findmy.extra.SCHEMA_VERSION"
        const val EXTRA_TOKEN = "com.openbubbles.findmy.extra.TOKEN"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_STATE = "state"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_REFRESHED_AT_MS = "refreshed_at_ms"
        const val KEY_DEVICES = "devices"
        const val KEY_OPAQUE_ID = "opaque_id"
        const val KEY_NAME = "name"
        const val KEY_EMOJI = "emoji"
        const val KEY_PRODUCT_ID = "product_id"
        const val KEY_BATTERY_LEVEL = "battery_level"
        const val KEY_HAS_BATTERY_LEVEL = "has_battery_level"
        const val KEY_VENDOR_ID = "vendor_id"
        const val KEY_MODEL = "model"
        const val KEY_SYSTEM_VERSION = "system_version"
        const val KEY_SHARED = "shared"
        const val KEY_HAS_LOCATION = "has_location"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_HORIZONTAL_ACCURACY = "horizontal_accuracy"
        const val KEY_STATUS = "status"
        const val KEY_CONFIDENCE = "confidence"
        const val KEY_TIMESTAMP_MS = "timestamp_ms"
        const val STATE_UNAUTHORIZED = "unauthorized"
        const val STATE_PROVIDER_NOT_RUNNING = "provider_not_running"
        const val STATE_ACCOUNT_REQUIRED = "account_required"
        const val STATE_KEYCHAIN_REQUIRED = "keychain_required"
        const val STATE_FIND_MY_SETUP_REQUIRED = "findmy_setup_required"
        const val STATE_READY = "ready"
        const val STATE_SYNC_FAILED = "sync_failed"
        const val STATE_NATIVE_UNAVAILABLE = "native_unavailable"
    }
}
