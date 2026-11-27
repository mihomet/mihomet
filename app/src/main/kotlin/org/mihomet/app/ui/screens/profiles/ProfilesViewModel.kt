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
import org.mihomet.app.core.AppException
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.stringRes
import org.mihomet.app.core.config.ConversionOutcome
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.data.subs.SubscriptionDownload
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.preferences.PerAppMode
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.ProfileValidation
import org.mihomet.app.data.subs.SubscriptionHeaders
import org.mihomet.app.data.subs.SubscriptionRepository
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Locale

data class ProfilesUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val isLoading: Boolean = false,
    val importInProgress: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    // External deep-link import awaiting explicit user confirmation (audit P0 §1.1):
    // never imported silently — the dialog shows the payload, its source and the
    // target profile name; import runs only after Confirm.
    val pendingImport: PendingImport? = null,
    // Behavior-header changes requested by the subscription panel (audit P0 §1.1):
    // applied ONLY after the user accepts the consent dialog.
    val behaviorConsent: BehaviorConsent? = null,
    val conversionDiagnostic: ConversionDiagnostic? = null
)

/**
 * An external import (deep link / share target) waiting for the user's explicit
 * confirmation. [source] is the raw referrer / originating package when known.
 */
data class PendingImport(
    val text: String,
    val source: String?,
    val previewName: String
)

/**
 * The exact behavior changes the panel requested via the Happ/Incy headers.
 * Built after a successful profile save; nothing is applied until the user
 * accepts — in particular the root/VPN mode switch ([tunModeHint]) NEVER
 * happens without this explicit consent.
 */
data class BehaviorConsent(
    val profileId: Long,
    val profileName: String,
    val autoConnect: Boolean = false,
    /** null = no per-app change; true = whitelist, false = blacklist. */
    val perAppWhitelist: Boolean? = null,
    val perAppUids: Set<Int> = emptySet(),
    /** null = no mode change; true = non-root VPN (TUN), false = root TProxy. */
    val tunModeHint: Boolean? = null
) {
    val hasChanges: Boolean
        get() = autoConnect || perAppWhitelist != null || tunModeHint != null
}

class ProfilesViewModel(
    private val context: Context,
    private val profileDao: ProfileDao,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val errorMessageFlow = MutableStateFlow<String?>(null)
    private val successMessageFlow = MutableStateFlow<String?>(null)
    private val pendingImportFlow = MutableStateFlow<PendingImport?>(null)
    private val behaviorConsentFlow = MutableStateFlow<BehaviorConsent?>(null)
    private val reportFlow = MutableStateFlow<ConversionDiagnostic?>(null)
    private val importInProgressFlow = MutableStateFlow(false)
    private val importIdle = combine(pendingImportFlow, importInProgressFlow, behaviorConsentFlow, reportFlow) {
        pending, busy, consent, report -> pending == null && !busy && consent == null && report == null
    }

    fun showReport(report: ConversionReport?) { reportFlow.value = report?.let { ConversionDiagnostic(report = it) } }

    fun dismissDiagnostic() { reportFlow.value = null }

    private fun recordImportFailure(error: Throwable) {
        reportFlow.value = (reportFlow.value ?: ConversionDiagnostic()).copy(
            failureCode = (error as? AppException)?.code ?: AppErrorCode.IMPORT_FAILED
        )
    }

    val uiState: StateFlow<ProfilesUiState> = combine(
        profileDao.getAllProfiles(),
        errorMessageFlow,
        successMessageFlow,
        pendingImportFlow,
        behaviorConsentFlow
    ) { profiles, error, success, pendingImport, behaviorConsent ->
        ProfilesUiState(
            profiles = profiles,
            errorMessage = error,
            successMessage = success,
            pendingImport = pendingImport,
            behaviorConsent = behaviorConsent
        )
    }.combine(reportFlow) { state, report -> state.copy(conversionDiagnostic = report) }
        .combine(importInProgressFlow) { state, busy -> state.copy(importInProgress = busy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfilesUiState(isLoading = true))

    init {
        // External deep-link / share-target imports arrive here (audit P0 §1.1):
        // parked into [pendingImportFlow] for the confirmation dialog — the
        // ViewModel NEVER imports on its own.
        viewModelScope.launch {
            org.mihomet.app.core.DeepLinkBus.flow.collect { import ->
                if (import.text.isNotBlank()) {
                    importIdle.first { it }
                    pendingImportFlow.value = PendingImport(
                        text = import.text,
                        source = import.source,
                        previewName = previewImportName(import.text)
                    )
                    // Keep later links queued until this confirmation, import and report finish.
                    importIdle.first { it }
                }
            }
        }
    }

    /** Dialog preview of the profile name the import would produce. */
    private fun previewImportName(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            val url = trimmed.lines().firstOrNull { it.isNotBlank() }?.trim() ?: trimmed
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
            if (!host.isNullOrBlank()) {
                return context.getString(R.string.profile_auto_name, host)
            }
        }
        return context.getString(R.string.profile_auto_name_import)
    }

    /** User confirmed the pending external import — run the real import now. */
    fun confirmPendingImport() {
        if (importInProgressFlow.value) return
        val pending = pendingImportFlow.value ?: return
        importFromClipboard(
            text = pending.text,
            onSuccess = { successMessageFlow.value = it },
            onError = { errorMessageFlow.value = it }
        )
        pendingImportFlow.value = null
    }

    /** User rejected the pending external import — drop it, nothing was fetched. */
    fun cancelPendingImport() {
        pendingImportFlow.value = null
    }

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

    fun addRemoteProfile(name: String = "", url: String, customHeaders: String = "{}") {
        launchImport(url, name, customHeaders, remote = true)
    }

    fun addBlankProfile(name: String) {
        launchImportJob({
            val content = withContext(Dispatchers.IO) {
                context.assets.open("default_config.yaml").bufferedReader().use { it.readText() }
            }
            importContent(content, name, "{}", remote = false)
        })
    }

    fun addLocalProfile(name: String, fileContent: String) {
        launchImport(fileContent, name, remote = false)
    }

    fun importFromClipboard(text: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        if (text.isBlank()) { onError(context.getString(R.string.clipboard_empty)); return }
        val payload = org.mihomet.app.core.DeepLinkParser.parse(text.trim()) ?: text.trim()
        launchImport(payload, remote = payload.startsWith("https://", true) || payload.startsWith("http://", true),
            onSuccess = onSuccess, onError = onError)
    }

    private fun launchImport(
        source: String,
        name: String = "",
        headers: String = "{}",
        remote: Boolean,
        onSuccess: (String) -> Unit = { successMessageFlow.value = it },
        onError: (String) -> Unit = { errorMessageFlow.value = it }
    ) {
        launchImportJob({ importContent(source, name, headers, remote) }, onSuccess, onError)
    }

    private fun launchImportJob(
        action: suspend () -> Result<String>,
        onSuccess: (String) -> Unit = { successMessageFlow.value = it },
        onError: (String) -> Unit = { errorMessageFlow.value = it }
    ) {
        if (!importInProgressFlow.compareAndSet(false, true)) return
        reportFlow.value = null
        viewModelScope.launch {
            try {
                action().fold(onSuccess) {
                    recordImportFailure(it)
                    onError(localizeError(it))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordImportFailure(e)
                onError(localizeError(e))
            } finally {
                importInProgressFlow.value = false
            }
        }
    }

    private suspend fun importContent(source: String, name: String, headers: String, remote: Boolean): Result<String> {
        return try {
            val normalizedHeaders = SubscriptionHeaders.toJson(SubscriptionHeaders.parse(headers))
            val download = if (remote) subscriptionRepository.downloadAndConvert(source.trim(), normalizedHeaders)
            else SubscriptionDownload(withContext(Dispatchers.Default) {
                UniversalConfigConverter.convertToMihomoYamlWithOutcome(source)
            })
            val diagnostic = ConversionDiagnostic(download.outcome.report, download.outcome.warnings)
            if (download.outcome.warnings.isNotEmpty()) reportFlow.value = diagnostic
            if (!isValidGeneratedConfig(download.outcome.yaml)) {
                reportFlow.value = diagnostic
                return Result.failure(AppException(AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG))
            }
            val resolvedName = resolveImportName(name, source, remote, download)
            val result = subscriptionRepository.addProfile(
                resolvedName, if (remote) ProfileType.REMOTE else ProfileType.LOCAL,
                download, if (remote) source.trim() else "", normalizedHeaders
            )
            requestBehaviorConsent(result.profile.id, result.profile.name, result.meta)
            reportFlow.value = diagnostic.takeIf { it.safeWarnings.isNotEmpty() }
            errorMessageFlow.value = null
            Result.success(appendWarnings(context.getString(R.string.profiles_added, resolvedName), result.outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            Result.failure(e)
        } catch (e: SubscriptionHeaders.HeaderValidationException) {
            Result.failure(IllegalArgumentException(headerViolationMessage(e.violation)))
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException(context.getString(R.string.profiles_import_error, localizeError(e))))
        }
    }

    private fun resolveImportName(name: String, source: String, remote: Boolean, download: SubscriptionDownload): String {
        if (name.isNotBlank()) return name.trim()
        download.meta.profileTitle?.takeIf { it.isNotBlank() }?.let { return it }
        if (remote) return previewImportName(source)
        val parsed = runCatching { Yaml().load<Map<String, Any>>(download.outcome.yaml) }.getOrNull()
        val proxies = (parsed?.get("proxies") as? List<*>).orEmpty()
        val firstName = (proxies.firstOrNull() as? Map<*, *>)?.get("name")?.toString()
        return if (proxies.size == 1 && !firstName.isNullOrBlank()) firstName
        else if (proxies.isNotEmpty()) context.resources.getQuantityString(R.plurals.profile_auto_name_import_count, proxies.size, proxies.size)
        else context.getString(R.string.profile_auto_name_import)
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
        reportFlow.value = null
        viewModelScope.launch {
            subscriptionRepository.refreshProfileWithOutcome(profile)
                .onSuccess { result ->
                    val outcome = result.outcome
                    reportFlow.value = ConversionDiagnostic(outcome.report, outcome.warnings).takeIf { it.safeWarnings.isNotEmpty() }
                    requestBehaviorConsent(result.profile.id, result.profile.name, result.meta)
                    errorMessageFlow.value = null
                    successMessageFlow.value = appendWarnings(context.getString(R.string.profiles_updated), outcome)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    recordImportFailure(e)
                    errorMessageFlow.value =
                        context.getString(R.string.profiles_update_failed, localizeError(e))
                }
        }
    }

    /**
     * Computes the behavior changes the Happ/Incy headers ("панель дистанционно
     * настраивает клиент") request after a successful profile save and parks them
     * in [behaviorConsentFlow] for the one-time consent dialog (audit P0 §1.1).
     *
     * NOTHING is applied here — not the profile selection, not per-app routing,
     * and in particular NOT the root/VPN mode switch (`tun-mode` →
     * [SettingsRepository.updateRootMode]), which only ever runs from
     * [applyBehaviorConsent] after the user explicitly accepted. Headers the
     * panel did not send leave the corresponding user settings untouched:
     * - `subscription-autoconnect` makes the profile the selected (active) one;
     * - `per-app-proxy-mode` + `per-app-proxy-list` switch per-app routing to the
     *   panel-provided package set (proposed only for a valid whitelist/blacklist
     *   hint together with a non-empty, resolvable package list);
     * - `tun-mode` explicit `true` maps to the non-root VPN service (TUN) and
     *   explicit `false` to the root TProxy mode.
     */
    private suspend fun requestBehaviorConsent(profileId: Long, profileName: String, meta: SubscriptionMeta) {
        val modeHint = meta.perAppModeHint?.trim()?.lowercase(Locale.ROOT)
        var perAppWhitelist: Boolean? = null
        var uids: Set<Int> = emptySet()
        if ((modeHint == "whitelist" || modeHint == "blacklist") && meta.perAppPackages.isNotEmpty()) {
            val resolved = withContext(Dispatchers.Default) {
                meta.perAppPackages.mapNotNull { pkg ->
                    // Packages that are not installed cannot resolve to a UID — skip them.
                    runCatching { context.packageManager.getPackageUid(pkg, 0) }.getOrNull()
                }.toSet()
            }
            if (resolved.isNotEmpty()) {
                perAppWhitelist = modeHint == "whitelist"
                uids = resolved
            }
        }

        val consent = BehaviorConsent(
            profileId = profileId,
            profileName = profileName,
            autoConnect = meta.autoConnect,
            perAppWhitelist = perAppWhitelist,
            perAppUids = uids,
            tunModeHint = meta.tunModeHint
        )
        if (consent.hasChanges) {
            behaviorConsentFlow.value = consent
        }
    }

    /**
     * Applies exactly the changes the user accepted in the consent dialog.
     * This is the ONLY path that may run [SettingsRepository.updateRootMode] on
     * behalf of a panel hint.
     */
    fun applyBehaviorConsent() {
        val consent = behaviorConsentFlow.value ?: return
        behaviorConsentFlow.value = null
        viewModelScope.launch {
            if (consent.autoConnect) {
                profileDao.selectProfile(consent.profileId)
            }
            val whitelist = consent.perAppWhitelist
            if (whitelist != null && consent.perAppUids.isNotEmpty()) {
                settingsRepository.updatePerAppMode(
                    if (whitelist) PerAppMode.WHITELIST else PerAppMode.BLACKLIST
                )
                settingsRepository.updateSelectedAppUids(consent.perAppUids)
            }
            consent.tunModeHint?.let { settingsRepository.updateRootMode(!it) }
        }
    }

    fun updateProfileInterval(profile: ProfileEntity, hours: Int) {
        viewModelScope.launch {
            profileDao.updateProfile(profile.copy(updateIntervalHours = hours))
        }
    }

    /** User declined the panel's behavior suggestions — nothing is changed. */
    fun declineBehaviorConsent() {
        behaviorConsentFlow.value = null
    }

    /**
     * Display-time localization of converter warnings (external audit §3.9):
     * the count suffix stays, and each typed warning is rendered through
     * [WarningLocalizer] in the UI locale — Russian literals from the data
     * layer can never leak into the EN UI.
     */
    private fun appendWarnings(message: String, outcome: ConversionOutcome): String {
        if (outcome.warnings.isEmpty()) return message
        if (outcome.report != null) return "$message ${context.getString(R.string.profiles_warnings_suffix, outcome.warnings.size)}"
        val details = outcome.warnings.joinToString(separator = "\n") { warning ->
            org.mihomet.app.core.config.WarningLocalizer.localize(warning, context)
        }
        return "$message ${context.getString(R.string.profiles_warnings_suffix, outcome.warnings.size)}\n$details"
    }

    /**
     * Validates and stores the per-profile custom subscription headers
     * ("Name: Value" lines or a JSON object map; see [SubscriptionHeaders] for
     * the strict guards — header-injection, size caps). A blank input clears
     * the custom headers. Validation runs synchronously so the caller can keep
     * the dialog open on failure; success/failure snackbars go through the
     * shared [ProfilesUiState.successMessage] / [ProfilesUiState.errorMessage] flows.
     */
    fun saveHeaders(profileId: Long, headersText: String): Result<Unit> {
        val normalized = try {
            SubscriptionHeaders.toJson(SubscriptionHeaders.parse(headersText))
        } catch (e: SubscriptionHeaders.HeaderValidationException) {
            val error = IllegalArgumentException(headerViolationMessage(e.violation))
            errorMessageFlow.value = error.message
            return Result.failure(error)
        }

        viewModelScope.launch {
            try {
                val entity = profileDao.getProfileById(profileId)
                    ?: throw IllegalArgumentException("Profile not found")
                // Headers may carry panel API tokens — encrypt before the write,
                // exactly like url / keyring values (audit I2 / F15b).
                val encrypted = try {
                    SecretsCipher.encryptIfSecret(normalized)
                } catch (e: SecretsCipher.SecretEncryptException) {
                    throw IllegalStateException(context.getString(R.string.profiles_encrypt_failed))
                }
                profileDao.updateProfile(
                    entity.copy(customHeaders = encrypted, lastUpdated = System.currentTimeMillis())
                )
                errorMessageFlow.value = null
                successMessageFlow.value = context.getString(R.string.profile_headers_saved)
            } catch (e: Exception) {
                errorMessageFlow.value =
                    context.getString(R.string.profiles_update_failed, localizeError(e))
            }
        }
        return Result.success(Unit)
    }

    /**
     * Resolves the decrypted custom headers of a remote profile as human-facing
     * `Name: Value` lines for the editor dialog. Empty string when the profile
     * has none (or is not remote); a decrypt failure is surfaced through
     * [ProfilesUiState.errorMessage] (reuses `profiles_subscription_undecryptable`).
     */
    suspend fun getHeadersText(profileId: Long): String = withContext(Dispatchers.IO) {
        val entity = profileDao.getProfileById(profileId) ?: return@withContext ""
        if (entity.type != ProfileType.REMOTE) return@withContext ""
        val stored = try {
            SecretsCipher.decrypt(entity.customHeaders)
        } catch (e: SecretsCipher.SecretDecryptException) {
            errorMessageFlow.value = context.getString(R.string.profiles_subscription_undecryptable)
            return@withContext ""
        }
        SubscriptionHeaders.toLines(SubscriptionHeaders.parseLenient(stored))
    }

    /**
     * Localizes a thrown error for display (audit P2.9 tail): typed [AppException]s
     * from the core/data layers map to string resources; anything else keeps the
     * legacy `e.message` fallback. Producers no longer carry UI-language strings.
     */
    private fun localizeError(e: Throwable): String {
        if (e is AppException) {
            val res = e.code.stringRes()
            return if (e.args.isEmpty()) {
                context.getString(res)
            } else {
                context.getString(res, *e.args)
            }
        }
        return e.message ?: context.getString(R.string.profiles_unknown_error)
    }

    /** Maps a pure parser [SubscriptionHeaders.Violation] to a localized message. */
    private fun headerViolationMessage(violation: SubscriptionHeaders.Violation): String =
        when (violation) {
            SubscriptionHeaders.Violation.RAW_TOO_LARGE ->
                context.getString(R.string.profiles_headers_error_size)
            SubscriptionHeaders.Violation.TOO_MANY_HEADERS ->
                context.getString(R.string.profiles_headers_error_too_many, SubscriptionHeaders.MAX_HEADERS)
            SubscriptionHeaders.Violation.NAME_TOO_LONG ->
                context.getString(R.string.profiles_headers_error_name_long)
            SubscriptionHeaders.Violation.VALUE_TOO_LONG ->
                context.getString(R.string.profiles_headers_error_value_long)
            SubscriptionHeaders.Violation.FORBIDDEN_CHAR ->
                context.getString(R.string.profiles_headers_error_chars)
            SubscriptionHeaders.Violation.EMPTY_NAME ->
                context.getString(R.string.profiles_headers_error_empty_name)
        }

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            profileDao.deleteProfile(profile)
            File(profile.filePath).delete()
        }
    }

    suspend fun getProfileContent(profileId: Long): String = withContext(Dispatchers.IO) {
        val entity = profileDao.getProfileById(profileId) ?: return@withContext ""
        val file = File(entity.filePath)
        if (file.exists()) file.readText() else ""
    }

    /**
     * Resolves the decrypted subscription URL of a remote profile for sharing
     * (QR dialog). Returns `null` when the profile is not remote, has no URL,
     * does not exist — or when the stored secret cannot be decrypted; in the
     * last case the user-facing explanation is surfaced through
     * [ProfilesUiState.errorMessage] (reuses `profiles_subscription_undecryptable`).
     */
    suspend fun getProfileUrl(profileId: Long): String? = withContext(Dispatchers.IO) {
        val profile = profileDao.getProfileById(profileId) ?: return@withContext null
        if (profile.type != ProfileType.REMOTE || profile.url.isBlank()) return@withContext null
        try {
            SecretsCipher.decrypt(profile.url).takeIf { it.isNotBlank() }
        } catch (e: SecretsCipher.SecretDecryptException) {
            errorMessageFlow.value = context.getString(R.string.profiles_subscription_undecryptable)
            null
        }
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
                    context.getString(R.string.profiles_update_failed, localizeError(e))
            }
        }
        return Result.success(Unit)
    }

    companion object {
        /**
         * Fail-closed validation of a converted profile before it is persisted
         * (subscription update, remote add, clipboard/deep-link import).
         *
         * Delegates to [ProfileValidation.isValid] — the single semantic
         * validator shared with [SubscriptionRepository]'s background refresh,
         * where the full contract (non-empty `proxies` / `proxy-providers` /
         * `rules`, rejection of `proxies: []` + `rules: []` broken-subscription
         * payloads) is documented.
         */
        fun isValidGeneratedConfig(yaml: String): Boolean = ProfileValidation.isValid(yaml)
    }
}
