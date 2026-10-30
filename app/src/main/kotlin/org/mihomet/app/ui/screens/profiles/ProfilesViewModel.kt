package org.mihomet.app.ui.screens.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mihomet.app.R
import org.mihomet.app.core.config.ConversionOutcome
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.preferences.PerAppMode
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.SubscriptionRepository
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Locale

data class ProfilesUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ProfilesViewModel(
    private val context: Context,
    private val profileDao: ProfileDao,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val errorMessageFlow = MutableStateFlow<String?>(null)
    private val successMessageFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProfilesUiState> = combine(
        profileDao.getAllProfiles(),
        errorMessageFlow,
        successMessageFlow
    ) { profiles, error, success ->
        ProfilesUiState(profiles = profiles, errorMessage = error, successMessage = success)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfilesUiState(isLoading = true))

    fun clearError() {
        errorMessageFlow.value = null
    }

    fun clearSuccess() {
        successMessageFlow.value = null
    }

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            profileDao.selectProfile(id)
        }
    }

    /**
     * Adds a remote profile. [name] may be blank — when the user leaves it empty,
     * the panel-provided `profile-title` header (if any) names the profile, and
     * otherwise a default name is derived from the URL host.
     */
    fun addRemoteProfile(name: String = "", url: String, customHeaders: String = "{}") {
        viewModelScope.launch {
            try {
                val profileDir = File(context.filesDir, "profiles").apply { mkdirs() }
                val normalizedHeaders = SubscriptionRepository.normalizeHeadersJson(customHeaders)
                val download = subscriptionRepository.downloadAndConvert(url, normalizedHeaders)
                // Fail-closed: never persist a conversion result without proxies,
                // proxy-providers or rules — the core would route everything to DIRECT.
                if (!isValidGeneratedConfig(download.outcome.yaml)) {
                    errorMessageFlow.value = context.getString(R.string.profiles_invalid_result)
                    return@launch
                }
                val meta = download.meta

                val file = File(profileDir, "profile_${System.currentTimeMillis()}.yaml")
                file.writeText(download.outcome.yaml)

                // Name priority: explicit user input > panel `profile-title` > auto name from URL host.
                val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: ""
                val resolvedName = when {
                    name.isNotBlank() -> name.trim()
                    !meta.profileTitle.isNullOrBlank() -> meta.profileTitle.trim()
                    host.isNotBlank() -> context.getString(R.string.profile_auto_name, host)
                    else -> context.getString(R.string.profile_auto_name_clipboard)
                }
                // Panel-initiated subscription relocation (New-Url / Fallback-Url): store the new URL.
                val effectiveUrl = meta.redirectUrl?.takeIf { !it.equals(url, ignoreCase = true) } ?: url

                val isFirst = profileDao.getSelectedProfile() == null
                val quota = download.userinfo
                val entity = ProfileEntity(
                    name = resolvedName,
                    type = ProfileType.REMOTE,
                    // url / custom_headers are encrypted at rest (see SecretsCipher).
                    url = SecretsCipher.encryptIfSecret(effectiveUrl),
                    filePath = file.absolutePath,
                    customHeaders = SecretsCipher.encryptIfSecret(normalizedHeaders),
                    isSelected = isFirst,
                    uploadUsed = quota?.uploadUsed ?: 0,
                    downloadUsed = quota?.downloadUsed ?: 0,
                    trafficTotal = quota?.trafficTotal ?: 0,
                    expireAt = quota?.expireAt ?: 0,
                    // Panel-recommended auto-update interval (hours); 0 disables background refresh.
                    updateIntervalHours = meta.updateIntervalHours
                )
                val newId = profileDao.insertProfile(entity)
                if (isFirst) {
                    profileDao.selectProfile(newId)
                }
                applyBehaviorHeaders(newId, meta)
                errorMessageFlow.value = null
                successMessageFlow.value = appendWarnings(context.getString(R.string.profiles_added, resolvedName), download.outcome)
            } catch (e: Exception) {
                errorMessageFlow.value = context.getString(R.string.profiles_add_failed, e.message ?: context.getString(R.string.profiles_unknown_error))
            }
        }
    }

    fun addBlankProfile(name: String) {
        viewModelScope.launch {
            val profileDir = File(context.filesDir, "profiles").apply { mkdirs() }
            val defaultYaml = context.assets.open("default_config.yaml").bufferedReader().use { it.readText() }
            val file = File(profileDir, "profile_${System.currentTimeMillis()}.yaml")
            file.writeText(defaultYaml)

            val isFirst = profileDao.getSelectedProfile() == null
            val entity = ProfileEntity(
                name = name,
                type = ProfileType.LOCAL,
                filePath = file.absolutePath,
                isSelected = isFirst
            )
            val newId = profileDao.insertProfile(entity)
            if (isFirst) {
                profileDao.selectProfile(newId)
            }
        }
    }

    fun importFromClipboard(
        text: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            onError(context.getString(R.string.clipboard_empty))
            return
        }

        viewModelScope.launch {
            try {
                if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                    val url = trimmed.lines().first { it.isNotBlank() }.trim()
                    val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: ""
                    val autoName = if (host.isNotBlank()) {
                        context.getString(R.string.profile_auto_name, host)
                    } else {
                        context.getString(R.string.profile_auto_name_clipboard)
                    }

                    val profileDir = File(context.filesDir, "profiles").apply { mkdirs() }
                    val download = subscriptionRepository.downloadAndConvert(url)
                    // Fail-closed: never persist a conversion result without proxies,
                    // proxy-providers or rules — the core would route everything to DIRECT.
                    if (!isValidGeneratedConfig(download.outcome.yaml)) {
                        onError(context.getString(R.string.profiles_invalid_result))
                        return@launch
                    }
                    val content = download.outcome.yaml
                    val file = File(profileDir, "profile_${System.currentTimeMillis()}.yaml")
                    file.writeText(content)

                    val isFirst = profileDao.getSelectedProfile() == null
                    val entity = ProfileEntity(
                        name = autoName,
                        type = ProfileType.REMOTE,
                        // Encrypted at rest (see SecretsCipher); blank stays blank.
                        url = SecretsCipher.encryptIfSecret(url),
                        filePath = file.absolutePath,
                        isSelected = isFirst
                    )
                    val newId = profileDao.insertProfile(entity)
                    if (isFirst) {
                        profileDao.selectProfile(newId)
                    }
                    onSuccess(context.getString(R.string.profiles_imported, autoName))
                } else {
                    val outcome = withContext(Dispatchers.Default) {
                        UniversalConfigConverter.convertToMihomoYamlWithOutcome(trimmed)
                    }
                    val convertedYaml = outcome.yaml

                    val yaml = Yaml()
                    val parsed = runCatching { yaml.load<Map<String, Any>>(convertedYaml) }.getOrNull()
                    val proxies = (parsed?.get("proxies") as? List<*>) ?: emptyList<Any>()

                    // Fail-closed: reject results without proxies / proxy-providers / rules
                    // instead of persisting a config the core would turn into "all DIRECT".
                    // rules-only results stay valid here (happ://routing deep-link imports).
                    if (!isValidGeneratedConfig(convertedYaml)) {
                        onError(context.getString(R.string.profiles_invalid_result))
                        return@launch
                    }

                    val firstMap = proxies.firstOrNull() as? Map<*, *>
                    val firstName = firstMap?.get("name")?.toString()
                    val autoName = if (proxies.size == 1 && !firstName.isNullOrBlank()) {
                        firstName
                    } else if (proxies.isNotEmpty()) {
                        context.resources.getQuantityString(R.plurals.profile_auto_name_import_count, proxies.size, proxies.size)
                    } else {
                        context.getString(R.string.profile_auto_name_import)
                    }

                    val profileDir = File(context.filesDir, "profiles").apply { mkdirs() }
                    val file = File(profileDir, "profile_${System.currentTimeMillis()}.yaml")
                    file.writeText(convertedYaml)

                    val isFirst = profileDao.getSelectedProfile() == null
                    val entity = ProfileEntity(
                        name = autoName,
                        type = ProfileType.LOCAL,
                        filePath = file.absolutePath,
                        isSelected = isFirst
                    )
                    val newId = profileDao.insertProfile(entity)
                    if (isFirst) {
                        profileDao.selectProfile(newId)
                    }
                    onSuccess(
                        if (proxies.isNotEmpty()) {
                            appendWarnings(context.getString(R.string.profiles_import_done, autoName), outcome)
                        } else {
                            context.getString(R.string.profiles_config_added)
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onError(context.getString(R.string.profiles_import_error, e.localizedMessage ?: e.message))
            }
        }
    }

    /**
     * Refreshes a remote profile and applies the panel behavior headers on success.
     *
     * The refresh is performed through [SubscriptionRepository.downloadAndConvert] and
     * persisted here (instead of `refreshProfileWithOutcome`) because the Happ/Incy
     * behavior headers (`subscription-autoconnect`, `per-app-proxy-*`, `tun-mode`)
     * must be applied after every successful save, and the refresh result does not
     * carry the parsed [SubscriptionMeta]. Persisted fields mirror the repository's
     * refresh semantics: a missing quota header keeps the stored values, the panel
     * URL migration and update interval are honored.
     */
    fun updateProfile(profile: ProfileEntity) {
        if (profile.type != ProfileType.REMOTE) return
        // url / custom_headers are encrypted at rest — decrypt before use; the
        // migrated URL is re-encrypted on write below.
        val url = try {
            SecretsCipher.decrypt(profile.url)
        } catch (e: org.mihomet.app.data.crypto.SecretsCipher.SecretDecryptException) {
            errorMessageFlow.value = context.getString(R.string.profiles_subscription_undecryptable)
            return
        }
        val headers = try {
            SecretsCipher.decrypt(profile.customHeaders)
        } catch (e: org.mihomet.app.data.crypto.SecretsCipher.SecretDecryptException) {
            errorMessageFlow.value = context.getString(R.string.profiles_subscription_undecryptable)
            return
        }
        if (url.isBlank()) return
        viewModelScope.launch {
            try {
                val download = subscriptionRepository.downloadAndConvert(url, headers)
                // Fail-closed: never overwrite the profile file with a conversion result
                // without proxies, proxy-providers or rules — keep the last good version
                // on disk so the running service can continue to use it.
                if (!isValidGeneratedConfig(download.outcome.yaml)) {
                    errorMessageFlow.value = context.getString(R.string.profiles_invalid_result)
                    return@launch
                }
                File(profile.filePath).writeText(download.outcome.yaml)
                // If the server sent no Subscription-Userinfo this time, keep the previous quota.
                val quota = download.userinfo
                // Panel-initiated subscription relocation (New-Url / Fallback-Url): auto-migrate.
                val migratedUrl = download.meta.redirectUrl
                    ?.takeIf { !it.equals(url, ignoreCase = true) } ?: url
                val updated = profile.copy(
                    url = SecretsCipher.encrypt(migratedUrl),
                    lastUpdated = System.currentTimeMillis(),
                    uploadUsed = quota?.uploadUsed ?: profile.uploadUsed,
                    downloadUsed = quota?.downloadUsed ?: profile.downloadUsed,
                    trafficTotal = quota?.trafficTotal ?: profile.trafficTotal,
                    expireAt = quota?.expireAt ?: profile.expireAt,
                    // Absent profile-update-interval keeps the previously stored interval.
                    updateIntervalHours = download.meta.updateIntervalHours
                        .takeIf { it > 0 } ?: profile.updateIntervalHours
                )
                profileDao.updateProfile(updated)
                applyBehaviorHeaders(updated.id, download.meta)
                errorMessageFlow.value = null
                successMessageFlow.value = appendWarnings(context.getString(R.string.profiles_updated), download.outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessageFlow.value =
                    context.getString(R.string.profiles_update_failed, e.message ?: context.getString(R.string.profiles_unknown_error))
            }
        }
    }

    /**
     * Applies the Happ/Incy behavior headers ("панель дистанционно настраивает
     * клиент") after a successful profile save. Headers the panel did not send
     * leave the corresponding user settings untouched:
     * - `subscription-autoconnect` makes the profile the selected (active) one;
     * - `per-app-proxy-mode` + `per-app-proxy-list` switch per-app routing to the
     *   panel-provided package set (applied only for a valid whitelist/blacklist
     *   hint together with a non-empty, resolvable package list);
     * - `tun-mode` explicit `true` maps to the non-root VPN service (TUN) and
     *   explicit `false` to the root TProxy mode.
     */
    private suspend fun applyBehaviorHeaders(profileId: Long, meta: SubscriptionMeta) {
        if (meta.autoConnect) {
            profileDao.selectProfile(profileId)
        }

        val modeHint = meta.perAppModeHint?.trim()?.lowercase(Locale.ROOT)
        if ((modeHint == "whitelist" || modeHint == "blacklist") && meta.perAppPackages.isNotEmpty()) {
            val packageManager = context.packageManager
            val uids = meta.perAppPackages.mapNotNull { pkg ->
                // Packages that are not installed cannot resolve to a UID — skip them.
                runCatching { packageManager.getPackageUid(pkg, 0) }.getOrNull()
            }.toSet()
            if (uids.isNotEmpty()) {
                settingsRepository.updatePerAppMode(
                    if (modeHint == "whitelist") PerAppMode.WHITELIST else PerAppMode.BLACKLIST
                )
                settingsRepository.updateSelectedAppUids(uids)
            }
        }

        meta.tunModeHint?.let { settingsRepository.updateRootMode(!it) }
    }

    private fun appendWarnings(message: String, outcome: ConversionOutcome): String {
        return if (outcome.warnings.isEmpty()) {
            message
        } else {
            "$message ${context.getString(R.string.profiles_warnings_suffix, outcome.warnings.size)}"
        }
    }

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            File(profile.filePath).delete()
            profileDao.deleteProfile(profile)
        }
    }

    suspend fun getProfileContent(profileId: Long): String = withContext(Dispatchers.IO) {
        val entity = profileDao.getProfileById(profileId) ?: return@withContext ""
        val file = File(entity.filePath)
        if (file.exists()) file.readText() else ""
    }

    suspend fun saveProfileContent(profileId: Long, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Validate YAML syntax
            val yaml = Yaml()
            yaml.load<Any>(newContent)

            val entity = profileDao.getProfileById(profileId)
                ?: throw IllegalArgumentException("Profile not found")
            File(entity.filePath).writeText(newContent)
            profileDao.updateProfile(entity.copy(lastUpdated = System.currentTimeMillis()))
        }
    }

    /**
     * Validates and stores the per-profile override patch (deep-merged into the
     * runtime config before the mandatory app-managed keys — see ConfigGenerator).
     * A blank patch clears the override. Validation runs synchronously so the caller
     * can keep the editor open on failure; success/failure snackbars go through the
     * shared [ProfilesUiState.successMessage] / [ProfilesUiState.errorMessage] flows.
     */
    fun saveOverridePatch(profileId: Long, patch: String): Result<Unit> {
        val trimmed = patch.trim()

        // Validate YAML syntax and shape before touching the DB. An empty patch is
        // valid (it clears the override); otherwise the fragment must be a mapping.
        if (trimmed.isNotEmpty()) {
            val parsed = try {
                Yaml().load<Any>(trimmed)
            } catch (e: Exception) {
                val reason = e.message?.lineSequence()?.firstOrNull()?.take(160) ?: ""
                val error = IllegalArgumentException(context.getString(R.string.profile_patch_invalid, reason))
                errorMessageFlow.value = error.message ?: context.getString(R.string.profiles_unknown_error)
                return Result.failure(error)
            }
            if (parsed != null && parsed !is Map<*, *>) {
                val error = IllegalArgumentException(context.getString(R.string.profile_patch_not_map))
                errorMessageFlow.value = error.message
                return Result.failure(error)
            }
        }

        viewModelScope.launch {
            try {
                val entity = profileDao.getProfileById(profileId)
                    ?: throw IllegalArgumentException("Profile not found")
                profileDao.updateProfile(
                    entity.copy(overridePatch = trimmed, lastUpdated = System.currentTimeMillis())
                )
                errorMessageFlow.value = null
                successMessageFlow.value = context.getString(R.string.profile_patch_saved)
            } catch (e: Exception) {
                errorMessageFlow.value =
                    context.getString(R.string.profiles_update_failed, e.message ?: context.getString(R.string.profiles_unknown_error))
            }
        }
        return Result.success(Unit)
    }

    companion object {
        /**
         * Fail-closed validation of a converted profile before it is persisted
         * (subscription update, remote add, clipboard/deep-link import).
         *
         * A conversion result is valid when it is non-blank, parses as a YAML mapping
         * and declares at least one source of routing truth: `proxies:` (inline nodes),
         * `proxy-providers:` (nodes fetched by the core at runtime) or `rules:`.
         *
         * Deliberately valid without inline proxies:
         *  - `proxy-providers`-only configs (nodes are downloaded by the core itself);
         *  - `rules`-only configs — the happ://routing deep-link import produces exactly
         *    such routing profiles (GLOBAL/DIRECT/REJECT targets with a MATCH rule).
         *
         * Invalid: blank content, non-mapping documents (scalar/list), and mappings
         * without any of the three keys (e.g. an empty shell left by a failed
         * conversion) — such a profile would make the core route everything to DIRECT.
         */
        fun isValidGeneratedConfig(yaml: String): Boolean {
            if (yaml.isBlank()) return false
            val parsed = runCatching { Yaml().load<Any>(yaml) }.getOrNull() ?: return false
            if (parsed !is Map<*, *>) return false
            return parsed.containsKey("proxies") ||
                parsed.containsKey("proxy-providers") ||
                parsed.containsKey("rules")
        }
    }
}
