package me.kavishdevar.librepods.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import me.kavishdevar.librepods.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uploads bounded, redacted feature-state events from debug builds only after opt-in.
 * Never send credentials, locations, biometric values, audio, addresses, or protocol payloads.
 */
object FeatureDiagnostics {
    private const val ENDPOINT = "https://204-168-163-118.sslip.io/librepods-diagnostics/v1/librepods"
    private const val PREFERENCES_NAME = "settings"
    private const val ENABLED_PREFERENCE = "support_diagnostics_enabled"
    private const val MAX_QUEUE = 64
    private const val MAX_EVENT_LENGTH = 64
    private const val MAX_LINE_LENGTH = 256
    private const val RETRY_DELAY_SECONDS = 30L
    private val queue = ArrayDeque<String>()
    private val draining = AtomicBoolean(false)
    private val retryScheduled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "librepods-feature-diag").apply { isDaemon = true }
    }

    fun isAvailable(): Boolean = BuildConfig.DEBUG

    fun isEnabled(context: Context): Boolean =
        isAvailable() && context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_PREFERENCE, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val accepted = enabled && isAvailable()
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED_PREFERENCE, accepted)
            .apply()
        if (!accepted) synchronized(queue) { queue.clear() }
    }

    fun event(context: Context, name: String) {
        if (!isEnabled(context)) return
        val safeName = name
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            .take(MAX_EVENT_LENGTH)
        if (safeName.isEmpty()) return
        val safeVersion = safeField(BuildConfig.VERSION_NAME, 32)
        val safeModel = safeField(Build.MODEL, 48)
        val line = listOf(
            "timestamp=${System.currentTimeMillis()}",
            "event=$safeName",
            "version=$safeVersion",
            "sdk=${Build.VERSION.SDK_INT}",
            "model=$safeModel",
        ).joinToString(" ").take(MAX_LINE_LENGTH)
        synchronized(queue) {
            while (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(line)
        }
        if (draining.compareAndSet(false, true)) {
            executor.execute { drain(context.applicationContext) }
        }
    }

    private fun drain(context: Context) {
        var uploadFailed = false
        try {
            while (true) {
                val line = synchronized(queue) { queue.firstOrNull() } ?: return
                if (!upload(context, line)) {
                    uploadFailed = true
                    return
                }
                synchronized(queue) {
                    if (queue.firstOrNull() == line) queue.removeFirst()
                }
            }
        } finally {
            draining.set(false)
            if (uploadFailed && isEnabled(context)) {
                scheduleRetry(context)
            } else if (
                synchronized(queue) { queue.isNotEmpty() } &&
                draining.compareAndSet(false, true)
            ) {
                executor.execute { drain(context) }
            }
        }
    }

    private fun scheduleRetry(context: Context) {
        if (!isEnabled(context)) return
        if (!retryScheduled.compareAndSet(false, true)) return
        executor.schedule({
            retryScheduled.set(false)
            if (isEnabled(context) && draining.compareAndSet(false, true)) drain(context)
        }, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun upload(context: Context, line: String): Boolean = runCatching {
        if (!isEnabled(context)) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) return false
        val connection = network.openConnection(URL(ENDPOINT)) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.outputStream.use { it.write(line.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun safeField(value: String, maximumLength: Int): String = value
        .map { character -> if (character == ' ') '_' else character }
        .filter { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character in ".-_"
        }
        .joinToString(separator = "")
        .take(maximumLength)
}
