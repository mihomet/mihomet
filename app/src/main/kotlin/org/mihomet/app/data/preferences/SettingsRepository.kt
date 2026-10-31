package org.mihomet.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.db.AppDatabase
import org.mihomet.app.data.db.ProfileDao
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mihomet_settings")

class SettingsRepository(
    private val context: Context,
    private val profileDao: ProfileDao
) {

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
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val LAST_MODE = stringPreferencesKey("last_mode")
        val TUN_STACK = stringPreferencesKey("tun_stack")
        val TUN_MTU = intPreferencesKey("tun_mtu")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val VPN_ALLOW_BYPASS = booleanPreferencesKey("vpn_allow_bypass")
        val VPN_HTTP_PROXY = booleanPreferencesKey("vpn_http_proxy")
        val BATT_PROMPT_SHOWN = booleanPreferencesKey("batt_prompt_shown")
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
                overrideSnifferEnabled = preferences[PreferencesKeys.OVERRIDE_SNIFFER_ENABLED] ?: true,
                autoStartOnBoot = preferences[PreferencesKeys.AUTO_START_ON_BOOT] ?: false,
                lastMode = preferences[PreferencesKeys.LAST_MODE]
                    ?.takeIf { it == LastRunMode.VPN || it == LastRunMode.ROOT }
                    .orEmpty(),
                tunStack = TunOptions.sanitizeStack(preferences[PreferencesKeys.TUN_STACK]),
                tunMtu = TunOptions.sanitizeMtu(preferences[PreferencesKeys.TUN_MTU]),
                bypassLan = preferences[PreferencesKeys.BYPASS_LAN] ?: false,
                vpnAllowBypass = preferences[PreferencesKeys.VPN_ALLOW_BYPASS] ?: false,
                vpnHttpProxy = preferences[PreferencesKeys.VPN_HTTP_PROXY] ?: false,
                battPromptShown = preferences[PreferencesKeys.BATT_PROMPT_SHOWN] ?: false
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
            val encrypted = encryptControllerSecretOrPlaintext(generated)
            context.dataStore.edit { preferences ->
                // Overwrite unconditionally: an undecryptable value is garbage
                preferences[PreferencesKeys.CONTROLLER_SECRET] = encrypted
            }
            cachedSecret = generated
            generated
        }
    }

    /**
     * External audit N07-2: [SecretsCipher.encrypt] now fails closed
     * ([SecretsCipher.SecretEncryptException]) when the Keystore key is missing
     * while encrypted data exists, instead of silently returning plaintext. The
     * controller secret is app-internal — losing its ciphertext costs at most a
     * regeneration — so this caller degrades to plaintext persistence (logged)
     * rather than blocking the whole settings stack.
     */
    private fun encryptControllerSecretOrPlaintext(value: String): String =
        try {
            SecretsCipher.encrypt(value)
        } catch (e: SecretsCipher.SecretEncryptException) {
            android.util.Log.e(
                TAG,
                "Controller secret cannot be encrypted (encryption key unavailable); storing it as plaintext",
                e
            )
            value
        }

    /**
     * Data-loss gate probe (external audit F12-а, extended by N07-1): `true`
     * when at least one `enc1:` value exists anywhere secrets are persisted,
     * i.e. data was encrypted under the AndroidKeyStore key and the cipher must
     * never (re)generate it. Wired into [SecretsCipher.keyGate] by
     * [org.mihomet.app.MihometApplication].
     *
     * Probed sources, cheapest first:
     * - the controller secret (DataStore);
     * - every stored Happ crypto keyring value (same DataStore snapshot);
     * - every profile `url` / `customHeaders` (Room, one-shot
     *   [ProfileDao.getAllProfilesOnce] query).
     *
     * The gate is CONSERVATIVE: a read error at any source (DataStore I/O,
     * Room failure or timeout, corrupted keyring JSON) is reported as `true` —
     * an error must never read as "no encrypted data", or a lost key would be
     * regenerated over it. The pure decision core is
     * [companion object][Companion.hasEncryptedSecrets].
     *
     * The Room probe is skipped while a Room migration is in flight
     * ([AppDatabase.roomMigrationInFlight]): the probe would run from inside
     * the migration thread and contend on the write connection the migration
     * holds, and the encrypting migration (4→5) operates on columns that are
     * plaintext by schema, so a probe can never contribute there.
     */
    suspend fun hasEncryptedSecrets(): Boolean {
        val prefs = try {
            context.dataStore.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(
                TAG,
                "Data-loss gate: DataStore read failed; conservatively assuming encrypted data exists",
                e
            )
            return true
        }
        val controllerSecret = prefs[PreferencesKeys.CONTROLLER_SECRET]
        if (controllerSecret?.startsWith(SecretsCipher.MARKER) == true) return true

        val keyringRaw = prefs[PreferencesKeys.HAPP_CRYPTO_KEYS]
        val keyringValues = if (keyringRaw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { Json.decodeFromString<Map<String, String>>(keyringRaw.trim()) }
                .getOrElse {
                    android.util.Log.e(
                        TAG,
                        "Data-loss gate: Happ keyring JSON unreadable; conservatively assuming encrypted data exists"
                    )
                    return true
                }
                .values.toList()
        }
        if (keyringValues.any { it.startsWith(SecretsCipher.MARKER) }) return true

        if (AppDatabase.roomMigrationInFlight) {
            // Room probe skipped (see KDoc); decide from the DataStore sources.
            return hasEncryptedSecrets(controllerSecret, emptyList(), keyringValues)
        }
        val roomRows = try {
            withTimeoutOrNull(ROOM_PROBE_TIMEOUT_MS) {
                profileDao.getAllProfilesOnce().map { it.url to it.customHeaders }
            } ?: run {
                android.util.Log.e(
                    TAG,
                    "Data-loss gate: Room probe timed out; conservatively assuming encrypted data exists"
                )
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(
                TAG,
                "Data-loss gate: Room probe failed; conservatively assuming encrypted data exists",
                e
            )
            return true
        }
        return hasEncryptedSecrets(controllerSecret, roomRows, keyringValues)
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
            // the facade passthrough then never yields the marker; an encryption
            // refusal degrades to plaintext via the helper and skips the write).
            if (!existing.startsWith(SecretsCipher.MARKER)) {
                val encrypted = encryptControllerSecretOrPlaintext(plain)
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
            val encrypted = encryptControllerSecretOrPlaintext(generated)
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

    /** Toggles the boot autostart feature (BootReceiver). */
    suspend fun updateAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_START_ON_BOOT] = enabled }
    }

    /**
     * Persists the last successfully started service mode ("vpn"/"root"). Called by
     * the services AFTER the core proved ready, so the BootReceiver only autostarts
     * modes that are known to start cleanly. Any other value is stored as "".
     */
    suspend fun updateLastMode(mode: String) {
        context.dataStore.edit {
            it[PreferencesKeys.LAST_MODE] =
                if (mode == LastRunMode.VPN || mode == LastRunMode.ROOT) mode else ""
        }
    }

    suspend fun updateTunStack(stack: String) {
        context.dataStore.edit { it[PreferencesKeys.TUN_STACK] = TunOptions.sanitizeStack(stack.trim()) }
    }

    suspend fun updateTunMtu(mtu: Int) {
        context.dataStore.edit { it[PreferencesKeys.TUN_MTU] = TunOptions.sanitizeMtu(mtu) }
    }

    suspend fun updateBypassLan(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BYPASS_LAN] = enabled }
    }

    /** Toggles VpnService allowBypass (apps with the bypass option may route around the VPN). */
    suspend fun updateVpnAllowBypass(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.VPN_ALLOW_BYPASS] = enabled }
    }

    /** Toggles the HTTP-proxy-via-VPN opt-in (VpnService setHttpProxy + mixed-port). */
    suspend fun updateVpnHttpProxy(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.VPN_HTTP_PROXY] = enabled }
    }

    /**
     * Persists that the one-time battery-optimization prompt has been displayed
     * (FlClash BatteryPlugin pattern): the dialog never nags twice per install.
     */
    suspend fun updateBatteryPromptShown(shown: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BATT_PROMPT_SHOWN] = shown }
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
     * At rest the values are `enc1:` ciphertext (F15b); this returns the decrypted
     * plaintext view — see [decodeCryptoKeys] for the failure semantics.
     */
    suspend fun getCryptoKeys(): Map<String, String> {
        val prefs = context.dataStore.data.first()
        return parseCryptoKeysJson(prefs[PreferencesKeys.HAPP_CRYPTO_KEYS])
    }

    /**
     * Adds or replaces the key stored under [id].
     *
     * Returns [Result.failure] wrapping [SecretsCipher.SecretEncryptException]
     * when the value cannot be encrypted (Keystore key unavailable while
     * encrypted data exists, external audit N07-2): the DataStore edit aborts
     * and the stored keyring is left untouched, so a raw AES key is never
     * silently downgraded to plaintext. The caller must surface the failure —
     * the user-typed key is not persisted and must be re-entered.
     */
    suspend fun setCryptoKey(id: String, key: String): Result<Unit> = try {
        context.dataStore.edit { prefs ->
            val updated = parseCryptoKeysJsonRaw(prefs[PreferencesKeys.HAPP_CRYPTO_KEYS]).toMutableMap()
            updated[id.trim()] = key.trim()
            // External audit F15b: every value is persisted encrypted (`enc1:`).
            // Legacy plaintext entries still present in the stored map are
            // opportunistically re-encrypted by the same write. An encryption
            // refusal propagates out of this transform and aborts the edit.
            prefs[PreferencesKeys.HAPP_CRYPTO_KEYS] =
                Json.encodeToString(encodeCryptoKeys(updated, SecretsCipher::encrypt))
        }
        Result.success(Unit)
    } catch (e: SecretsCipher.SecretEncryptException) {
        android.util.Log.e(
            TAG,
            "Happ key '$id' cannot be encrypted (encryption key unavailable); keyring left unchanged",
            e
        )
        Result.failure(e)
    }

    /** Removes the key stored under [id] (no-op when absent). */
    suspend fun removeCryptoKey(id: String) {
        context.dataStore.edit { prefs ->
            // Raw parse (no decrypt): removal must not silently drop entries whose
            // ciphertext happens to be undecryptable — untouched values pass through
            // this edit byte-for-byte.
            val updated = parseCryptoKeysJsonRaw(prefs[PreferencesKeys.HAPP_CRYPTO_KEYS]).toMutableMap()
            updated.remove(id.trim())
            prefs[PreferencesKeys.HAPP_CRYPTO_KEYS] = Json.encodeToString(updated)
        }
    }

    /**
     * Plaintext view of the stored keyring (read path for subscription decryption
     * and the UI): `enc1:` values are decrypted, undecryptable entries are skipped,
     * legacy plaintext passes through — see [decodeCryptoKeys].
     */
    private fun parseCryptoKeysJson(raw: String?): Map<String, String> =
        decodeCryptoKeys(parseCryptoKeysJsonRaw(raw), SecretsCipher::decrypt)

    /**
     * Decodes the stored JSON mapping as-is (values may be `enc1:` ciphertext or
     * legacy plaintext); a missing or corrupted entry degrades to an empty keyring
     * instead of crashing.
     */
    private fun parseCryptoKeysJsonRaw(raw: String?): Map<String, String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(trimmed)
        }.getOrElse { emptyMap() }
    }

    companion object {
        private const val TAG = "SettingsRepository"

        /** Upper bound for the data-loss gate's Room probe (see [hasEncryptedSecrets]). */
        private const val ROOM_PROBE_TIMEOUT_MS = 3_000L

        /**
         * Pure decision core of the data-loss gate [hasEncryptedSecrets]
         * (JVM-testable, external audit N07-1): `true` when any probed source
         * carries the [SecretsCipher.MARKER]. [datastoreSecret] is the stored
         * controller secret (may be null/blank), [roomRows] are the
         * `url to customHeaders` pairs of all profiles, [keyringValues] are the
         * raw stored keyring values (`enc1:` ciphertext or legacy plaintext).
         * Read errors are handled upstream (conservative `true`) — this core is
         * total and never throws.
         */
        internal fun hasEncryptedSecrets(
            datastoreSecret: String?,
            roomRows: List<Pair<String, String>>,
            keyringValues: List<String>
        ): Boolean =
            datastoreSecret?.startsWith(SecretsCipher.MARKER) == true ||
                roomRows.any { (url, headers) ->
                    url.startsWith(SecretsCipher.MARKER) || headers.startsWith(SecretsCipher.MARKER)
                } ||
                keyringValues.any { it.startsWith(SecretsCipher.MARKER) }

        /**
         * At-rest encryption of the Happ crypto keyring (external audit F15b): the
         * stored JSON keeps its `{id: value}` shape, but every value is written
         * through [SecretsCipher.encryptIfSecret] so it lands as `enc1:` ciphertext
         * instead of the raw AES key. The [encryptFn] seam is JVM-testable;
         * production passes the Keystore-backed [SecretsCipher.encrypt], which
         * either encrypts, degrades to passthrough (Keystore unusable wholesale —
         * documented), or throws [SecretsCipher.SecretEncryptException] (fail
         * closed while encrypted data exists, external audit N07-2).
         */
        internal fun encodeCryptoKeys(
            stored: Map<String, String>,
            encryptFn: (String) -> String
        ): Map<String, String> =
            stored.mapValues { (_, value) -> SecretsCipher.encryptIfSecret(value, encryptFn) }

        /**
         * Plaintext view of the stored keyring, produced with the [decryptFn] seam
         * (production: [SecretsCipher.decrypt], which throws
         * [SecretsCipher.SecretDecryptException] on undecryptable payloads):
         * - an `enc1:` value that decrypts → its plaintext;
         * - an `enc1:` value that does NOT decrypt (Keystore key replaced or lost) →
         *   SKIPPED with a log: the raw payload is not a usable secret, and the
         *   keyring is small enough for the user to re-enter the key in Settings;
         * - a value without the marker (legacy pre-F15b plaintext, or the JVM
         *   passthrough mode) → passed through unchanged so existing subscriptions
         *   keep decrypting; the next keyring write re-encrypts it
         *   ([encodeCryptoKeys]).
         */
        internal fun decodeCryptoKeys(
            stored: Map<String, String>,
            decryptFn: (String) -> String
        ): Map<String, String> {
            val plaintext = LinkedHashMap<String, String>(stored.size)
            for ((id, value) in stored) {
                if (!value.startsWith(SecretsCipher.MARKER)) {
                    if (value.isNotBlank()) {
                        android.util.Log.w(
                            TAG,
                            "Happ key '$id' is stored unencrypted (legacy); it will be encrypted on the next keyring edit"
                        )
                    }
                    plaintext[id] = value
                    continue
                }
                try {
                    plaintext[id] = decryptFn(value)
                } catch (e: SecretsCipher.SecretDecryptException) {
                    android.util.Log.w(
                        TAG,
                        "Happ key '$id' is undecryptable (encryption key unavailable or replaced); skipping — re-add the key in Settings"
                    )
                }
            }
            return plaintext
        }
    }

    // endregion
}
