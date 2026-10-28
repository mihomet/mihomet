package org.mihomet.app.ui.screens.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import org.mihomet.app.data.subs.SubscriptionRepository
import org.yaml.snakeyaml.Yaml
import java.io.File

data class ProfilesUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ProfilesViewModel(
    private val context: Context,
    private val profileDao: ProfileDao,
    private val subscriptionRepository: SubscriptionRepository
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
                    url = effectiveUrl,
                    filePath = file.absolutePath,
                    customHeaders = normalizedHeaders,
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
                    val content = subscriptionRepository.downloadAndConvert(url).outcome.yaml
                    val file = File(profileDir, "profile_${System.currentTimeMillis()}.yaml")
                    file.writeText(content)

                    val isFirst = profileDao.getSelectedProfile() == null
                    val entity = ProfileEntity(
                        name = autoName,
                        type = ProfileType.REMOTE,
                        url = url,
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

                    if (proxies.isEmpty() && !convertedYaml.contains("proxies:")) {
                        onError(context.getString(R.string.profiles_unrecognized))
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

    fun updateProfile(profile: ProfileEntity) {
        if (profile.type != ProfileType.REMOTE || profile.url.isBlank()) return
        viewModelScope.launch {
            subscriptionRepository.refreshProfileWithOutcome(profile)
                .onSuccess { (_, outcome) ->
                    errorMessageFlow.value = null
                    successMessageFlow.value = appendWarnings(context.getString(R.string.profiles_updated), outcome)
                }
                .onFailure { e ->
                    errorMessageFlow.value =
                        context.getString(R.string.profiles_update_failed, e.message ?: context.getString(R.string.profiles_unknown_error))
                }
        }
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
}
