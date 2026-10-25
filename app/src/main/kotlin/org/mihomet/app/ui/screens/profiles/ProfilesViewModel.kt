package org.mihomet.app.ui.screens.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Base64

data class ProfilesUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ProfilesViewModel(
    private val context: Context,
    private val profileDao: ProfileDao
) : ViewModel() {

    val uiState: StateFlow<ProfilesUiState> = profileDao.getAllProfiles()
        .map { ProfilesUiState(profiles = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfilesUiState(isLoading = true))

    private val httpClient = HttpClient()

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            profileDao.selectProfile(id)
        }
    }

    fun addRemoteProfile(name: String, url: String, customHeaders: String = "{}") {
        viewModelScope.launch {
            try {
                val profileDir = File(context.filesDir, "profiles").apply { mkdirs() }
                val content = downloadProfileContent(url, customHeaders)

                val file = File(profileDir, "profile_${System.currentTimeMillis()}.yaml")
                file.writeText(content)

                val isFirst = profileDao.getSelectedProfile() == null
                val entity = ProfileEntity(
                    name = name,
                    type = ProfileType.REMOTE,
                    url = url,
                    filePath = file.absolutePath,
                    customHeaders = customHeaders,
                    isSelected = isFirst
                )
                val newId = profileDao.insertProfile(entity)
                if (isFirst) {
                    profileDao.selectProfile(newId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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

    fun updateProfile(profile: ProfileEntity) {
        if (profile.type != ProfileType.REMOTE || profile.url.isBlank()) return
        viewModelScope.launch {
            try {
                val content = downloadProfileContent(profile.url, profile.customHeaders)
                File(profile.filePath).writeText(content)
                profileDao.updateProfile(profile.copy(lastUpdated = System.currentTimeMillis()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    suspend fun downloadProfileContent(url: String, headersJson: String = "{}"): String = withContext(Dispatchers.IO) {
        val parsedHeaders = runCatching {
            Json.decodeFromString<Map<String, String>>(headersJson)
        }.getOrDefault(emptyMap())

        val responseText: String = httpClient.get(url) {
            header("User-Agent", parsedHeaders["User-Agent"] ?: "ClashMeta; mihomo/1.19.33")
            parsedHeaders.forEach { (k, v) ->
                if (!k.equals("User-Agent", ignoreCase = true)) {
                    header(k, v)
                }
            }
        }.body()

        // If Base64 encoded subscription, decode it
        val trimmed = responseText.trim()
        if (!trimmed.startsWith("port:") &&
            !trimmed.startsWith("mixed-port:") &&
            !trimmed.startsWith("proxies:") &&
            !trimmed.startsWith("#")
        ) {
            runCatching {
                val decoded = Base64.getDecoder().decode(trimmed)
                String(decoded)
            }.getOrDefault(responseText)
        } else {
            responseText
        }
    }
}
