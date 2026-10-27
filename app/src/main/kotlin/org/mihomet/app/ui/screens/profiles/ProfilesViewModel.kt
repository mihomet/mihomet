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

    fun addRemoteProfile(name: String, url: String, customHeaders: String = "{}") {
        viewModelScope.launch {
            try {
                val profileDir = File(context.filesDir, "profiles").apply { mkdirs() }
                val normalizedHeaders = normalizeHeadersJson(customHeaders)
                val outcome = downloadAndConvertOutcome(url, normalizedHeaders)

                val file = File(profileDir, "profile_${System.currentTimeMillis()}.yaml")
                file.writeText(outcome.yaml)

                val isFirst = profileDao.getSelectedProfile() == null
                val entity = ProfileEntity(
                    name = name,
                    type = ProfileType.REMOTE,
                    url = url,
                    filePath = file.absolutePath,
                    customHeaders = normalizedHeaders,
                    isSelected = isFirst
                )
                val newId = profileDao.insertProfile(entity)
                if (isFirst) {
                    profileDao.selectProfile(newId)
                }
                errorMessageFlow.value = null
                successMessageFlow.value = appendWarnings("Подписка «$name» добавлена", outcome)
            } catch (e: Exception) {
                errorMessageFlow.value = "Не удалось загрузить подписку: ${e.message ?: "неизвестная ошибка"}"
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
            onError("Буфер обмена пуст")
            return
        }

        viewModelScope.launch {
            try {
                if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                    val url = trimmed.lines().first { it.isNotBlank() }.trim()
                    val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: ""
                    val autoName = if (host.isNotBlank()) "Подписка $host" else "Подписка из буфера"

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
                    onSuccess("Подписка «$autoName» успешно добавлена")
                } else {
                    val outcome = withContext(Dispatchers.Default) {
                        UniversalConfigConverter.convertToMihomoYamlWithOutcome(trimmed)
                    }
                    val convertedYaml = outcome.yaml

                    val yaml = Yaml()
                    val parsed = runCatching { yaml.load<Map<String, Any>>(convertedYaml) }.getOrNull()
                    val proxies = (parsed?.get("proxies") as? List<*>) ?: emptyList<Any>()

                    if (proxies.isEmpty() && !convertedYaml.contains("proxies:")) {
                        onError("Не удалось распознать формат узлов в буфере обмена")
                        return@launch
                    }

                    val firstMap = proxies.firstOrNull() as? Map<*, *>
                    val firstName = firstMap?.get("name")?.toString()
                    val autoName = if (proxies.size == 1 && !firstName.isNullOrBlank()) {
                        firstName
                    } else if (proxies.isNotEmpty()) {
                        "Импорт (${proxies.size} узлов)"
                    } else {
                        "Импортированная конфигурация"
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
                            appendWarnings("Импортировано $autoName", outcome)
                        } else {
                            "Конфигурация успешно добавлена"
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onError("Ошибка импорта: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    fun updateProfile(profile: ProfileEntity) {
        if (profile.type != ProfileType.REMOTE || profile.url.isBlank()) return
        viewModelScope.launch {
            try {
                val outcome = downloadAndConvertOutcome(profile.url, profile.customHeaders)
                File(profile.filePath).writeText(outcome.yaml)
                profileDao.updateProfile(profile.copy(lastUpdated = System.currentTimeMillis()))
                errorMessageFlow.value = null
                successMessageFlow.value = appendWarnings("Подписка обновлена", outcome)
            } catch (e: Exception) {
                errorMessageFlow.value =
                    "Не удалось обновить подписку: ${e.message ?: "неизвестная ошибка"}"
            }
        }
    }

    private fun appendWarnings(message: String, outcome: ConversionOutcome): String {
        return if (outcome.warnings.isEmpty()) {
            message
        } else {
            "$message (пропущено элементов: ${outcome.warnings.size})"
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

        val customUa = parsedHeaders["User-Agent"]
        val userAgentsToTry = if (!customUa.isNullOrBlank()) {
            listOf(customUa)
        } else {
            listOf(
                "Happ/3.6.0 (Android; Xray)",
                "sing-box/1.10.0 (Android)",
                "ClashMeta/1.18.0",
                "v2rayNG/1.8.19"
            )
        }

        var responseText = ""
        var lastException: Exception? = null

        for (ua in userAgentsToTry) {
            try {
                val resp = httpClient.get(url) {
                    header("User-Agent", ua)
                    parsedHeaders.forEach { (k, v) ->
                        if (!k.equals("User-Agent", ignoreCase = true)) {
                            header(k, v)
                        }
                    }
                }
                if (resp.status.value in 200..299) {
                    val contentLength = resp.headers["Content-Length"]?.toLongOrNull()
                    if (contentLength != null && contentLength > MAX_SUBSCRIPTION_BYTES) {
                        throw IllegalStateException("Ответ подписки слишком большой (>20 МБ)")
                    }
                    val body: String = resp.body()
                    if (body.isNotBlank()) {
                        responseText = body
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

        UniversalConfigConverter.convertToMihomoYamlWithOutcome(decoded)
    }

    private companion object {
        const val MAX_SUBSCRIPTION_BYTES = 20L * 1024 * 1024
    }
}
