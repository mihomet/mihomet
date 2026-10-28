package org.mihomet.app.ui.screens.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mihomet.app.R
import org.mihomet.app.core.HwidProvider
import org.mihomet.app.core.config.ConversionOutcome
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Base64

data class ProfilesUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ProfilesViewModel(
    private val context: Context,
    private val profileDao: ProfileDao
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

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15000
            requestTimeoutMillis = 30000
        }
    }

    /**
     * Stable device id sent as `x-hwid` with every subscription request
     * (required by the Happ/Incy panel ecosystem; user-provided header wins).
     */
    private val hwid: String by lazy { HwidProvider.get(context) }

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            profileDao.selectProfile(id)
        }
    }

    private fun normalizeHeadersJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "{}"
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        val map = trimmed.lines()
            .map { it.trim() }
            .filter { it.contains(":") || it.contains("=") }
            .associate { line ->
                val delimiter = if (line.contains(":")) ":" else "="
                val parts = line.split(delimiter, limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
        return Json.encodeToString(map)
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
                val normalizedHeaders = normalizeHeadersJson(customHeaders)
                val download = downloadSubscription(url, normalizedHeaders)
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
                    expireAt = quota?.expireAt ?: 0
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
                    val content = downloadProfileContent(url)
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
            try {
                val download = downloadSubscription(profile.url, profile.customHeaders)
                File(profile.filePath).writeText(download.outcome.yaml)
                // If the server sent no Subscription-Userinfo this time, keep the previous quota.
                val quota = download.userinfo
                // Panel-initiated subscription relocation (New-Url / Fallback-Url): auto-migrate.
                val migratedUrl = download.meta.redirectUrl
                    ?.takeIf { !it.equals(profile.url, ignoreCase = true) } ?: profile.url
                profileDao.updateProfile(
                    profile.copy(
                        url = migratedUrl,
                        lastUpdated = System.currentTimeMillis(),
                        uploadUsed = quota?.uploadUsed ?: profile.uploadUsed,
                        downloadUsed = quota?.downloadUsed ?: profile.downloadUsed,
                        trafficTotal = quota?.trafficTotal ?: profile.trafficTotal,
                        expireAt = quota?.expireAt ?: profile.expireAt
                    )
                )
                errorMessageFlow.value = null
                successMessageFlow.value = appendWarnings(context.getString(R.string.profiles_updated), download.outcome)
            } catch (e: Exception) {
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

    suspend fun downloadProfileContent(url: String, headersJson: String = "{}"): String =
        downloadAndConvertOutcome(url, headersJson).yaml

    private suspend fun downloadAndConvertOutcome(url: String, headersJson: String = "{}"): ConversionOutcome =
        downloadSubscription(url, headersJson).outcome

    private data class SubscriptionDownload(
        val outcome: ConversionOutcome,
        val userinfo: SubscriptionUserinfo? = null,
        val meta: SubscriptionMeta = SubscriptionMeta()
    )

    private suspend fun downloadSubscription(url: String, headersJson: String = "{}"): SubscriptionDownload =
        withContext(Dispatchers.IO) {
        val parsedHeaders = runCatching {
            Json.decodeFromString<Map<String, String>>(headersJson)
        }.getOrElse {
            headersJson.lines()
                .map { it.trim() }
                .filter { it.contains(":") || it.contains("=") }
                .associate { line ->
                    val delimiter = if (line.contains(":")) ":" else "="
                    val parts = line.split(delimiter, limit = 2)
                    parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                }
        }

        val customUa = parsedHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        // Honest UA first: strict SRR panels (Remnawave) serve content by UA tier, so
        // "Mihomet" must land in the Mihomo tier — no fake Happ priority. The other
        // well-known clients stay as fallbacks for panels that gate on them.
        val userAgentsToTry = if (!customUa.isNullOrBlank()) {
            listOf(customUa)
        } else {
            listOf(
                APP_USER_AGENT,
                "Happ/3.6.0 (Android; Xray)",
                "sing-box/1.10.0 (Android)",
                "ClashMeta/1.18.0",
                "v2rayNG/1.8.19"
            )
        }

        val hasCustomHwid = parsedHeaders.keys.any { it.equals(HwidProvider.HWID_HEADER, ignoreCase = true) }

        var responseText = ""
        var userinfo: SubscriptionUserinfo? = null
        var meta = SubscriptionMeta()
        var lastException: Exception? = null

        for (ua in userAgentsToTry) {
            try {
                val resp = httpClient.get(url) {
                    header("User-Agent", ua)
                    // x-hwid accompanies every attempt (user-provided header wins)
                    if (!hasCustomHwid) {
                        header(HwidProvider.HWID_HEADER, hwid)
                    }
                    parsedHeaders.forEach { (k, v) ->
                        if (!k.equals("User-Agent", ignoreCase = true)) {
                            header(k, v)
                        }
                    }
                }
                if (resp.status.value in 200..299) {
                    val contentLength = resp.headers["Content-Length"]?.toLongOrNull()
                    if (contentLength != null && contentLength > MAX_SUBSCRIPTION_BYTES) {
                        throw IllegalStateException(context.getString(R.string.profiles_too_large))
                    }
                    val body: String = resp.body()
                    if (body.isNotBlank()) {
                        responseText = body
                        // Ktor Headers lookup is case-insensitive; format:
                        // "upload=123 download=456 total=789 expire=1700000000"
                        userinfo = SubscriptionUserinfoParser.parse(resp.headers["Subscription-Userinfo"])
                        // Happ/Incy panel metadata: profile-title, profile-update-interval, New-Url
                        meta = SubscriptionMetaParser.parse(
                            resp.headers.names().mapNotNull { name ->
                                resp.headers[name]?.let { name to it }
                            }.toMap()
                        )
                        break
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (responseText.isBlank()) {
            if (lastException != null) throw lastException
            throw IllegalStateException("Empty or unsuccessful response from subscription URL")
        }

        // If Base64 encoded subscription, decode it
        val trimmed = responseText.trim()
        val decoded = if (!trimmed.startsWith("port:") &&
            !trimmed.startsWith("mixed-port:") &&
            !trimmed.startsWith("proxies:") &&
            !trimmed.startsWith("{") &&
            !trimmed.startsWith("[") &&
            !trimmed.startsWith("#")
        ) {
            runCatching {
                val clean = trimmed.replace("\n", "").replace("\r", "").replace(" ", "").trim()
                val padded = when (clean.length % 4) {
                    2 -> "$clean=="
                    3 -> "$clean="
                    else -> clean
                }
                val bytes = try {
                    Base64.getDecoder().decode(padded)
                } catch (e: Exception) {
                    Base64.getUrlDecoder().decode(padded)
                }
                String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            }.getOrDefault(responseText)
        } else {
            responseText
        }

        UniversalConfigConverter.convertToMihomoYamlWithOutcome(decoded).let { outcome ->
            SubscriptionDownload(outcome = outcome, userinfo = userinfo, meta = meta)
        }
    }

    private companion object {
        const val MAX_SUBSCRIPTION_BYTES = 20L * 1024 * 1024

        // TODO: derive from BuildConfig.VERSION_NAME once buildFeatures { buildConfig = true }
        //  is enabled in app/build.gradle.kts (it is currently off, so the version is inlined).
        const val APP_VERSION = "1.0"
        const val APP_USER_AGENT = "Mihomet/$APP_VERSION (Android; Mihomo)"
    }
}
