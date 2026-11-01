package org.mihomet.app

import android.app.Application
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.mihomet.app.data.api.AppLogBuffer
import org.mihomet.app.data.api.MihomoApiClientProvider
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.SubscriptionRefreshWorker
import org.mihomet.app.di.appModule

class MihometApplication : Application() {

    /**
     * Application-owned scope for the background Keystore data-loss gate probe
     * (audit P2.2: the probe used to `runBlocking` inside onCreate, blocking
     * the main thread through DataStore's first read + the Room probe). It
     * never runs user-facing work; [onTerminate] cancels it.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Verdict of the data-loss gate probe: `null` while the background probe
     * is still running (right after onCreate), then the computed value. Read
     * by the [SecretsCipher.keyGate] lambda below.
     */
    private val keyGateVerdict = MutableStateFlow<Boolean?>(null)

    override fun onCreate() {
        super.onCreate()

        // Configure LibSu
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        // Start Koin
        val koin = startKoin {
            androidLogger()
            androidContext(this@MihometApplication)
            modules(appModule)
        }.koin

        // Fail-closed Keystore gate (external audit F12-а, extended by N07-1):
        // SecretsCipher must never (re)generate the AndroidKeyStore key while
        // `enc1:` data still exists — generating over an existing alias
        // permanently destroys every stored secret. The gate answers "is there
        // encrypted data on disk?" conservatively across ALL places an `enc1:`
        // value can live — the controller secret (DataStore), the Happ crypto
        // keyring (DataStore JSON) and profile url/customHeaders (Room) — and
        // reports any read error as "data exists" (an error must never read as
        // absence of data). It is only consulted when the key alias is missing
        // or unreadable, never on a healthy startup.
        //
        // Audit P2.2: the probe runs ASYNCHRONOUSLY (it used to runBlocking
        // here, blocking the main thread). The gate lambda consumes the
        // verdict: in the normal case the probe has finished long before any
        // cipher use and the answer is a plain volatile read; if a cipher
        // caller races the probe it briefly awaits completion on ITS OWN
        // (Dispatchers.IO) thread — SecretsCipher documents that resolveKey
        // callers must never run on the main thread with a blocking gate.
        // A pathological hang degrades to the conservative verdict (`true`)
        // after a bounded wait: fail-closed stays fail-closed.
        val settingsRepository = koin.get<SettingsRepository>()
        appScope.launch {
            keyGateVerdict.value = settingsRepository.hasEncryptedSecrets()
        }
        SecretsCipher.keyGate = {
            keyGateVerdict.value ?: runBlocking {
                withTimeoutOrNull(KEY_GATE_AWAIT_TIMEOUT_MS) {
                    keyGateVerdict.filterNotNull().first()
                } ?: true // probe lost/timed out → conservatively "encrypted data exists"
            }
        }

        // Periodic background subscription refresh (WorkManager, KEEP policy).
        // Never blocks or breaks app startup if WorkManager init fails.
        runCatching {
            SubscriptionRefreshWorker.schedulePeriodicRefresh(this)
        }.onFailure {
            Log.w("MihometApplication", "Failed to schedule subscription refresh", it)
        }

        // Persistent background logging controller
        val clientProvider = koin.get<MihomoApiClientProvider>()
        appScope.launch {
            settingsRepository.settingsFlow.map { it.persistentLogging }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    AppLogBuffer.startContinuousLogging(clientProvider)
                } else {
                    AppLogBuffer.stopContinuousLogging()
                }
            }
        }
    }

    override fun onTerminate() {
        // Last-resort cleanup for the application-owned scope (not called on
        // real devices in normal operation; unit-test/emulator paths use it).
        appScope.cancel()
        super.onTerminate()
    }

    private companion object {
        /** Upper bound for racing gate consumers waiting on the async probe. */
        const val KEY_GATE_AWAIT_TIMEOUT_MS = 5_000L
    }
}
