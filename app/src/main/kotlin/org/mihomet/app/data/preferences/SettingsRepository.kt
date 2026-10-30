package org.mihomet.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.crypto.SecretsCipher
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mihomet_settings")

class SettingsRepository(private val context: Context) {

    // Guard against racing collectors generating different secrets in parallel
    @Volatile
    private var cachedSecret: String? = null
    private val secretMutex = Mutex()

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
        val SELECTED_NODE_NAME = stringPreferencesKey("selected_node_name")
        val SHOW_SPEED_NOTIFICATION = booleanPreferencesKey("show_speed_notification")
        val CUSTOM_DNS = stringPreferencesKey("custom_dns")
        val CONTROLLER_SECRET = stringPreferencesKey("controller_secret")
        val TEST_URL = stringPreferencesKey("test_url")
        val TEST_TIMEOUT_MS = intPreferencesKey("test_timeout_ms")
        val OVERRIDE_UNIFIED_DELAY = booleanPreferencesKey("override_unified_delay")
        val OVERRIDE_TCP_CONCURRENT = booleanPreferencesKey("override_tcp_concurrent")
        val OVERRIDE_LOG_LEVEL = stringPreferencesKey("override_log_level")
        val OVERRIDE_SNIFFER_ENABLED = booleanPreferencesKey("override_sniffer_enabled")
        val HAPP_CRYPTO_KEYS = stringPreferencesKey("happ_crypto_keys")
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
                isRootMode = preferences[PreferencesKeys.IS_ROOT_MODE] ?: false,
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
                selectedNodeName = preferences[PreferencesKeys.SELECTED_NODE_NAME] ?: "",
                mixedPort = preferences[PreferencesKeys.MIXED_PORT] ?: CoreConstants.DEFAULT_MIXED_PORT,
                tproxyPort = preferences[PreferencesKeys.TPROXY_PORT] ?: CoreConstants.DEFAULT_TPROXY_PORT,
                dnsPort = preferences[PreferencesKeys.DNS_PORT] ?: CoreConstants.DEFAULT_DNS_PORT,
                controllerPort = preferences[PreferencesKeys.CONTROLLER_PORT] ?: CoreConstants.DEFAULT_CONTROLLER_PORT,
                ipv6Enabled = preferences[PreferencesKeys.IPV6_ENABLED] ?: false,
                dynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true,
                darkTheme = preferences[PreferencesKeys.DARK_THEME] ?: true,
                amoledBlack = preferences[PreferencesKeys.AMOLED_BLACK] ?: false,
                showSpeedInNotification = preferences[PreferencesKeys.SHOW_SPEED_NOTIFICATION] ?: true,
                customDns = preferences[PreferencesKeys.CUSTOM_DNS] ?: "",
                controllerSecret = resolveControllerSecret(preferences[PreferencesKeys.CONTROLLER_SECRET]),
                testUrl = preferences[PreferencesKeys.TEST_URL]?.let { stored ->
                    if (stored.startsWith("https://cp.cloudflare.com")) {
                        stored.replace("https://", "http://")
                    } else stored
                } ?: "http://www.gstatic.com/generate_204",
                testTimeoutMs = preferences[PreferencesKeys.TEST_TIMEOUT_MS] ?: 5000,
                overrideUnifiedDelay = preferences[PreferencesKeys.OVERRIDE_UNIFIED_DELAY] ?: false,
                overrideTcpConcurrent = preferences[PreferencesKeys.OVERRIDE_TCP_CONCURRENT] ?: true,
                overrideLogLevel = preferences[PreferencesKeys.OVERRIDE_LOG_LEVEL]
                    ?.takeIf { ProfileOverrides.isValidLogLevel(it) }
                    ?: ProfileOverrides.DEFAULT_LOG_LEVEL,
                overrideSnifferEnabled = preferences[PreferencesKeys.OVERRIDE_SNIFFER_ENABLED] ?: true
            )
        }

    // Memoized (stored → plaintext) pair: settingsFlow re-maps on every DataStore
    // emission, and each SecretsCipher decrypt is a real AndroidKeyStore operation.
    @Volatile
    private var decryptedSecretCache: Pair<String, String>? = null

    /**
     * Returns the decrypted controller secret, or `null` when the stored value
     * carries the encrypted marker but cannot be decrypted (Keystore key
     * replaced or lost). `null` means the caller must regenerate — the
     * controller secret is app-internal, so a fresh value is always safe.
     */
    private fun decryptControllerSecret(stored: String): String? {
        decryptedSecretCache?.let { (encrypted, plain) ->
            if (encrypted == stored) return plain
        }
        return try {
            val plain = SecretsCipher.decrypt(stored)
            decryptedSecretCache = stored to plain
            plain
        } catch (e: SecretsCipher.SecretDecryptException) {
            null
        }
    }

    private suspend fun regenerateControllerSecret(): String {
        return secretMutex.withLock {
            cachedSecret?.let { return it }
            val generated = CoreConstants.generateRandomHexSecret()
            val encrypted = SecretsCipher.encrypt(generated)
            context.dataStore.edit { preferences ->
                // Overwrite unconditionally: an undecryptable value is garbage
                preferences[PreferencesKeys.CONTROLLER_SECRET] = encrypted
            }
            cachedSecret = generated
            generated
        }
    }

    /**
     * Returns the controller secret, or generates a random one on first use.
     * The secret is persisted encrypted ([SecretsCipher]); legacy plaintext
     * values pass through [SecretsCipher.decrypt] unchanged. Generation is
     * guarded by a mutex plus a volatile cache so parallel collectors produce
     * a single value; the write only happens if the key is still blank. The
     * resulting re-emission carries the persisted (encrypted) secret.
     */
    private suspend fun resolveControllerSecret(stored: String?): String {
        stored?.takeIf { it.isNotBlank() }?.let { existing ->
            val plain = decryptControllerSecret(existing)
                ?: run {
                    // Undecryptable (key replaced or lost): the controller secret is
                    // app-internal — regenerate a fresh one instead of surfacing garbage
                    android.util.Log.w("SettingsRepository", "Controller secret undecryptable; regenerating")
                    return regenerateControllerSecret()
                }
            // One-time legacy migration: a pre-encryption plaintext secret is
            // re-persisted encrypted (no-op when the Keystore is unavailable —
            // the facade passthrough then never yields the marker).
            if (!existing.startsWith(SecretsCipher.MARKER)) {
                val encrypted = SecretsCipher.encrypt(plain)
                if (encrypted.startsWith(SecretsCipher.MARKER)) {
                    context.dataStore.edit { preferences ->
                        if (preferences[PreferencesKeys.CONTROLLER_SECRET] == existing) {
                            preferences[PreferencesKeys.CONTROLLER_SECRET] = encrypted
                        }
                    }
                }
            }
            return plain
        }
        cachedSecret?.let { return it }
        return secretMutex.withLock {
            cachedSecret?.let { return it }
            val generated = CoreConstants.generateRandomHexSecret()
            val encrypted = SecretsCipher.encrypt(generated)
            context.dataStore.edit { preferences ->
                // Overwrite unconditionally: an undecryptable value is garbage
                preferences[PreferencesKeys.CONTROLLER_SECRET] = encrypted
            }
            cachedSecret = generated
            generated
        }
    }

    suspend fun updateSelectedNodeName(name: String) {
        context.dataStore.edit { it[PreferencesKeys.SELECTED_NODE_NAME] = name }
    }

    suspend fun updateShowSpeedInNotification(show: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SHOW_SPEED_NOTIFICATION] = show }
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

    suspend fun updateTestUrl(url: String) {
        val sanitized = if (url.startsWith("https://cp.cloudflare.com")) {
            url.replace("https://", "http://")
        } else url
        context.dataStore.edit { it[PreferencesKeys.TEST_URL] = sanitized }
    }

    suspend fun updateTestTimeout(timeoutMs: Int) {
        context.dataStore.edit { it[PreferencesKeys.TEST_TIMEOUT_MS] = timeoutMs }
    }

    suspend fun updateCustomDns(dns: String) {
        context.dataStore.edit { it[PreferencesKeys.CUSTOM_DNS] = dns.trim() }
    }

    suspend fun updateOverrideUnifiedDelay(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.OVERRIDE_UNIFIED_DELAY] = enabled }
    }

    suspend fun updateOverrideTcpConcurrent(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.OVERRIDE_TCP_CONCURRENT] = enabled }
    }

    suspend fun updateOverrideLogLevel(level: String) {
        context.dataStore.edit {
            it[PreferencesKeys.OVERRIDE_LOG_LEVEL] =
                if (ProfileOverrides.isValidLogLevel(level)) level else ProfileOverrides.DEFAULT_LOG_LEVEL
        }
    }

    suspend fun updateOverrideSnifferEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.OVERRIDE_SNIFFER_ENABLED] = enabled }
    }

    // region Happ crypto keyring (encrypted subscriptions, see core/HappCrypto.kt)

    /**
     * Reactive view of the stored Happ crypto keys (key id → AES key), for UI
     * keyring screens. A corrupted entry degrades to an empty keyring.
     */
    val cryptoKeysFlow: Flow<Map<String, String>> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> parseCryptoKeysJson(preferences[PreferencesKeys.HAPP_CRYPTO_KEYS]) }

    /**
     * Returns all stored Happ crypto keys as a mapping of key id → AES key
     * (the key is kept as a raw 16-byte ASCII string, e.g. "key02" → "key02:+]%4ij#P\"/").
     */
    suspend fun getCryptoKeys(): Map<String, String> {
        val prefs = context.dataStore.data.first()
        return parseCryptoKeysJson(prefs[PreferencesKeys.HAPP_CRYPTO_KEYS])
    }

    /** Adds or replaces the key stored under [id]. */
    suspend fun setCryptoKey(id: String, key: String) {
        context.dataStore.edit { prefs ->
            val updated = parseCryptoKeysJson(prefs[PreferencesKeys.HAPP_CRYPTO_KEYS]).toMutableMap()
            updated[id.trim()] = key.trim()
            prefs[PreferencesKeys.HAPP_CRYPTO_KEYS] = Json.encodeToString(updated)
        }
    }

    /** Removes the key stored under [id] (no-op when absent). */
    suspend fun removeCryptoKey(id: String) {
        context.dataStore.edit { prefs ->
            val updated = parseCryptoKeysJson(prefs[PreferencesKeys.HAPP_CRYPTO_KEYS]).toMutableMap()
            updated.remove(id.trim())
            prefs[PreferencesKeys.HAPP_CRYPTO_KEYS] = Json.encodeToString(updated)
        }
    }

    /**
     * Decodes the stored JSON mapping `{"key02": "key", ...}`; a missing or
     * corrupted entry degrades to an empty keyring instead of crashing.
     */
    private fun parseCryptoKeysJson(raw: String?): Map<String, String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(trimmed)
        }.getOrElse { emptyMap() }
    }

    // endregion
}
