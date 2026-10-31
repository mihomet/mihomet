package org.mihomet.app

import android.app.Application
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.SubscriptionRefreshWorker
import org.mihomet.app.di.appModule

class MihometApplication : Application() {

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

        // Fail-closed Keystore gate (external audit F12-а): SecretsCipher must
        // never (re)generate the AndroidKeyStore key while `enc1:` data still
        // exists — generating over an existing alias permanently destroys every
        // stored secret. The gate answers "is there encrypted data on disk?"
        // with a single DataStore read and is only consulted when the key alias
        // is absent (fresh install / lost key), never on a healthy startup.
        // `runBlocking` is acceptable here: one-shot lookup, wired before any
        // cipher use in this process, quick after DataStore's first in-memory
        // read; resolveKey callers run on Dispatchers.IO contexts.
        val settingsRepository = koin.get<SettingsRepository>()
        SecretsCipher.keyGate = { runBlocking { settingsRepository.hasEncryptedSecrets() } }

        // Periodic background subscription refresh (WorkManager, KEEP policy).
        // Never blocks or breaks app startup if WorkManager init fails.
        runCatching {
            SubscriptionRefreshWorker.schedulePeriodicRefresh(this)
        }.onFailure {
            Log.w("MihometApplication", "Failed to schedule subscription refresh", it)
        }
    }
}
