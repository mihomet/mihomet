package org.mihomet.app.data.analytics

import android.content.Context
import com.aptabase.Aptabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.mihomet.app.BuildConfig
import org.mihomet.app.core.config.ConversionOutcome
import org.mihomet.app.data.preferences.SettingsRepository
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Best-effort, privacy-first analytics boundary.
 *
 * Aptabase is not initialized until both conditions are true:
 * the build provides an app key and the user has explicitly opted in. The
 * tracker only emits bounded aggregate fields from [AnalyticsEventPayload].
 */
class AnalyticsTracker(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val consentGate = AnalyticsConsentGate()
    private val initializationGate = AnalyticsInitializationGate()
    private val processEventGate = ProcessEventGate()
    private val pendingTracks = ConcurrentHashMap.newKeySet<Job>()
    private val appKey = BuildConfig.APTABASE_APP_KEY.trim()
    private var settingsJob: Job? = null

    fun start(settingsRepository: SettingsRepository) {
        if (!hasValidAppKey() || settingsJob != null) return
        settingsJob = scope.launch {
            settingsRepository.settingsFlow
                .map { it.analyticsEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!consentGate.updateFromSettings(enabled)) {
                        cancelPendingTracks()
                        return@collect
                    }
                    val consent = consentGate.capture() ?: return@collect
                    track(consent, AnalyticsEventPayload.APP_STARTED, claimEvent = processEventGate::claimAppStarted)
                }
        }
    }

    fun trackConversion(outcome: ConversionOutcome, success: Boolean) {
        val consent = consentGate.capture() ?: return
        track(
            consent,
            AnalyticsEventPayload.CONVERSION_COMPLETED,
            AnalyticsEventPayload.conversionProperties(outcome.format, success, outcome.warnings.size),
            processEventGate::claimConversion
        )
    }

    /** Apply the user's choice before the settings write can emit an older value. */
    fun updateConsentFromUser(enabled: Boolean) {
        consentGate.requestFromUser(enabled)
        if (!enabled) cancelPendingTracks()
    }

    private fun initializeIfNeeded(): Boolean = initializationGate.initializeIfNeeded {
        Aptabase.instance.initialize(appContext, appKey)
    }

    private fun track(
        consent: Any,
        eventName: String,
        properties: Map<String, Any> = emptyMap(),
        claimEvent: () -> Boolean = { true }
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!initializeIfNeeded()) return@launch
            consentGate.handoff(consent) {
                if (claimEvent()) {
                    runCatching { Aptabase.instance.trackEvent(eventName, properties) }
                }
            }
        }
        pendingTracks += job
        job.invokeOnCompletion { pendingTracks.remove(job) }
        job.start()
    }

    private fun cancelPendingTracks() {
        // shortcut: Aptabase exposes no cancellation API; this only stops tracker jobs before SDK handoff.
        pendingTracks.toList().forEach { it.cancel() }
        pendingTracks.clear()
    }

    override fun close() {
        consentGate.requestFromUser(false)
        cancelPendingTracks()
        scope.cancel()
    }

    companion object {
        internal fun hasValidAppKey(): Boolean = AnalyticsEventPayload.isValidAppKey(BuildConfig.APTABASE_APP_KEY)
    }
}

internal class AnalyticsInitializationGate {
    private val lock = Any()
    private var initialized = false

    fun initializeIfNeeded(initialize: () -> Unit): Boolean = synchronized(lock) {
        if (initialized) return@synchronized true
        try {
            initialize()
            initialized = true
            true
        } catch (_: Exception) {
            // Analytics must never affect startup or user-facing workflows.
            false
        }
    }
}

internal object AnalyticsEventPayload {
    const val APP_STARTED = "app_started"
    const val CONVERSION_COMPLETED = "conversion_completed"

    private val knownFormats = setOf(
        "clash-yaml",
        "happ-routing",
        "sing-box",
        "uri-list",
        "wireguard",
        "xray"
    )

    private val publicAppKeyPattern = Regex("A-(US|EU)-[A-Za-z0-9_]+")

    fun conversionProperties(format: String?, success: Boolean, warningCount: Int): Map<String, Any> =
        mapOf(
            "format" to normalizeFormat(format),
            "success" to if (success) "success" else "failure",
            "warning_count" to warningCount.coerceIn(0, 100)
        )

    fun normalizeFormat(format: String?): String =
        format?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in knownFormats } ?: "unknown"

    fun isValidAppKey(appKey: String): Boolean = appKey.trim().matches(publicAppKeyPattern)
}

// Each opt-in period gets a unique token; disable and SDK handoff share the same lock.
internal class AnalyticsConsentGate {
    private val lock = Any()
    private var consent: Any? = null
    private var requestedByUser: Boolean? = null

    fun requestFromUser(enabled: Boolean) = synchronized(lock) {
        requestedByUser = enabled
        if (!enabled) consent = null
    }

    fun updateFromSettings(enabled: Boolean): Boolean = synchronized(lock) {
        val requested = requestedByUser
        if (requested != null && requested != enabled) {
            return@synchronized false
        }
        requestedByUser = null
        if (enabled != (consent != null)) {
            consent = if (enabled) Any() else null
        }
        enabled
    }

    fun capture(): Any? = synchronized(lock) { consent }

    fun handoff(token: Any?, action: () -> Unit): Boolean = synchronized(lock) {
        if (token == null || token !== consent) return@synchronized false
        action()
        true
    }
}

internal class ProcessEventGate {
    private val lock = Any()
    private var appStartedTracked = false
    private var conversionEventsTracked = 0

    fun claimAppStarted(): Boolean = synchronized(lock) {
        if (appStartedTracked) return@synchronized false
        appStartedTracked = true
        true
    }

    fun claimConversion(): Boolean = synchronized(lock) {
        if (conversionEventsTracked >= MAX_CONVERSION_EVENTS_PER_PROCESS) return@synchronized false
        conversionEventsTracked++
        true
    }

    private companion object {
        const val MAX_CONVERSION_EVENTS_PER_PROCESS = 50
    }
}
