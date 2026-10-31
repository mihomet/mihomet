package org.mihomet.app.di

import androidx.room.Room
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.firewall.FirewallManager
import org.mihomet.app.data.api.MihomoApiClientProvider
import org.mihomet.app.data.db.AppDatabase
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.SubscriptionRepository
import org.mihomet.app.ui.screens.connections.ConnectionsViewModel
import org.mihomet.app.ui.screens.dashboard.DashboardViewModel
import org.mihomet.app.ui.screens.logs.LogsViewModel
import org.mihomet.app.ui.screens.profiles.ProfilesViewModel
import org.mihomet.app.ui.screens.proxies.ProxiesViewModel
import org.mihomet.app.ui.screens.routing.RoutingViewModel
import org.mihomet.app.ui.screens.settings.SettingsViewModel

val appModule = module {
    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "mihomet.db"
        ).addMigrations(*AppDatabase.MIGRATIONS).build()
    }

    single { get<AppDatabase>().profileDao() }

    // Repositories & Managers
    // SettingsRepository needs ProfileDao for the data-loss gate probe
    // (SettingsRepository.hasEncryptedSecrets, external audit N07-1); Room is
    // built lazily and only opened when the probe actually runs.
    single { SettingsRepository(androidContext(), get()) }
    single { CoreManager(androidContext()) }
    single { FirewallManager() }
    single { SubscriptionRepository(androidContext(), get(), get()) }

    // API Client (recreated only when controller port/secret changes)
    single { MihomoApiClientProvider(get()) }

    // ViewModels
    viewModel { DashboardViewModel(get(), get(), get(), get()) }
    viewModel { ProxiesViewModel(get(), get(), get(), get(), androidContext()) }
    viewModel { ProfilesViewModel(androidContext(), get(), get(), get()) }
    viewModel { RoutingViewModel(androidContext(), get()) }
    viewModel { ConnectionsViewModel(get()) }
    viewModel { LogsViewModel(get(), androidContext()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
}
