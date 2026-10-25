package org.mihomet.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mihomet_settings")

class SettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val IS_ROOT_MODE = booleanPreferencesKey("is_root_mode")
        val FIREWALL_ENGINE = stringPreferencesKey("firewall_engine")
        val PER_APP_MODE = stringPreferencesKey("per_app_mode")
        val SELECTED_APP_UIDS = stringSetPreferencesKey("selected_app_uids")
        val PROXY_MODE = stringPreferencesKey("proxy_mode")
        val MIXED_PORT = intPreferencesKey("mixed_port")
        val TPROXY_PORT = intPreferencesKey("tproxy_port")
        val DNS_PORT = intPreferencesKey("dns_port")
        val CONTROLLER_PORT = intPreferencesKey("controller_port")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val CONTROLLER_SECRET = stringPreferencesKey("controller_secret")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                isRootMode = preferences[PreferencesKeys.IS_ROOT_MODE] ?: true,
                firewallEngine = runCatching {
                    FirewallEngineType.valueOf(preferences[PreferencesKeys.FIREWALL_ENGINE] ?: FirewallEngineType.AUTO.name)
                }.getOrDefault(FirewallEngineType.AUTO),
                perAppMode = runCatching {
                    PerAppMode.valueOf(preferences[PreferencesKeys.PER_APP_MODE] ?: PerAppMode.ALL.name)
                }.getOrDefault(PerAppMode.ALL),
                selectedAppUids = preferences[PreferencesKeys.SELECTED_APP_UIDS]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
                proxyMode = runCatching {
                    ProxyMode.valueOf(preferences[PreferencesKeys.PROXY_MODE] ?: ProxyMode.RULE.name)
                }.getOrDefault(ProxyMode.RULE),
                mixedPort = preferences[PreferencesKeys.MIXED_PORT] ?: 7890,
                tproxyPort = preferences[PreferencesKeys.TPROXY_PORT] ?: 7893,
                dnsPort = preferences[PreferencesKeys.DNS_PORT] ?: 1053,
                controllerPort = preferences[PreferencesKeys.CONTROLLER_PORT] ?: 9090,
                ipv6Enabled = preferences[PreferencesKeys.IPV6_ENABLED] ?: false,
                dynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true,
                darkTheme = preferences[PreferencesKeys.DARK_THEME] ?: true,
                amoledBlack = preferences[PreferencesKeys.AMOLED_BLACK] ?: false,
                controllerSecret = preferences[PreferencesKeys.CONTROLLER_SECRET] ?: "mihomet_secret_token"
            )
        }

    suspend fun updateRootMode(isRoot: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IS_ROOT_MODE] = isRoot }
    }

    suspend fun updateFirewallEngine(engine: FirewallEngineType) {
        context.dataStore.edit { it[PreferencesKeys.FIREWALL_ENGINE] = engine.name }
    }

    suspend fun updatePerAppMode(mode: PerAppMode) {
        context.dataStore.edit { it[PreferencesKeys.PER_APP_MODE] = mode.name }
    }

    suspend fun updateSelectedAppUids(uids: Set<Int>) {
        context.dataStore.edit { it[PreferencesKeys.SELECTED_APP_UIDS] = uids.map { uid -> uid.toString() }.toSet() }
    }

    suspend fun updateProxyMode(mode: ProxyMode) {
        context.dataStore.edit { it[PreferencesKeys.PROXY_MODE] = mode.name }
    }

    suspend fun updatePorts(mixed: Int, tproxy: Int, dns: Int, controller: Int) {
        context.dataStore.edit {
            it[PreferencesKeys.MIXED_PORT] = mixed
            it[PreferencesKeys.TPROXY_PORT] = tproxy
            it[PreferencesKeys.DNS_PORT] = dns
            it[PreferencesKeys.CONTROLLER_PORT] = controller
        }
    }

    suspend fun updateIpv6Enabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IPV6_ENABLED] = enabled }
    }

    suspend fun updateTheme(dynamic: Boolean, dark: Boolean, amoled: Boolean) {
        context.dataStore.edit {
            it[PreferencesKeys.DYNAMIC_COLOR] = dynamic
            it[PreferencesKeys.DARK_THEME] = dark
            it[PreferencesKeys.AMOLED_BLACK] = amoled
        }
    }
}
