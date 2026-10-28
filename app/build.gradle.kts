import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Mihomo core is kept out of git and downloaded at build time.
// Pinned version + sha256 live in gradle/mihomo-core.properties.
val coreProperties = Properties().apply {
    val file = rootProject.file("gradle/mihomo-core.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val coreVersion = coreProperties.getProperty("version") ?: "1.19.33"
val coreCacheDir = rootProject.layout.projectDirectory.dir(".mihomo-core-cache")

val downloadMihomoCore by tasks.registering {
    group = "build"
    description = "Downloads the pinned mihomo core binary and geo databases into the app module"
    doLast {
        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        fun download(url: String, dest: File, expectedSha: String?) {
            logger.lifecycle("Mihomet: downloading ${dest.name}…")
            dest.parentFile.mkdirs()
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            connection.instanceFollowRedirects = true
            try {
                connection.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (expectedSha != null) {
                val actual = sha256(dest)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    dest.delete()
                    throw GradleException(
                        "Checksum mismatch for ${dest.name}: expected $expectedSha, got $actual. " +
                            "If upstream published a new artifact, update gradle/mihomo-core.properties."
                    )
                }
            } else if (dest.length() < 1_000_000) {
                dest.delete()
                throw GradleException("Downloaded ${dest.name} looks truncated (${dest.length()} bytes).")
            }
        }

        // Core binary: pinned release, verified by sha256 of the .gz artifact
        val flavorByAbi = mapOf(
            "arm64-v8a" to "arm64-v8",
            "armeabi-v7a" to "armv7",
            "x86_64" to "amd64"
        )
        flavorByAbi.forEach { (abi, flavor) ->
            val target = layout.projectDirectory.file("src/main/jniLibs/$abi/libmihomo.so").asFile
            val expectedSize = coreProperties.getProperty("size.$abi")?.toLongOrNull()
            if (target.isFile && expectedSize != null && target.length() == expectedSize) {
                return@forEach
            }
            val gz = coreCacheDir.file("mihomo-android-$flavor-v$coreVersion.gz").asFile
            val expectedSha = coreProperties.getProperty("checksum.$abi")
                ?: throw GradleException("gradle/mihomo-core.properties is missing checksum.$abi")
            if (!gz.isFile || !sha256(gz).equals(expectedSha, ignoreCase = true)) {
                download(
                    "https://github.com/MetaCubeX/mihomo/releases/download/v$coreVersion/mihomo-android-$flavor-v$coreVersion.gz",
                    gz,
                    expectedSha
                )
            }
            target.parentFile.mkdirs()
            GZIPInputStream(gz.inputStream()).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            logger.lifecycle("Mihomet: extracted libmihomo.so ($abi) from core v$coreVersion")
        }

        // Geo databases: rolling latest, downloaded only when missing, sanity-checked by size.
        // (Data files consumed by the core — not executed — so no checksum pinning; keeps builds
        //  self-healing while the core binary above stays strictly pinned.)
        mapOf("geoip.metadb" to "geoip", "geosite.dat" to "geosite").forEach { (fileName, _) ->
            val target = layout.projectDirectory.file("src/main/assets/$fileName").asFile
            if (target.isFile && target.length() > 1_000_000) {
                return@forEach
            }
            val cached = coreCacheDir.file(fileName).asFile
            if (!cached.isFile || cached.length() < 1_000_000) {
                download(
                    "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/$fileName",
                    cached,
                    null
                )
            }
            target.parentFile.mkdirs()
            cached.copyTo(target, overwrite = true)
            logger.lifecycle("Mihomet: updated assets/$fileName")
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadMihomoCore) }

android {
    namespace = "org.mihomet.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.mihomet.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI release signing: provided via KEYSTORE_* environment variables.
            // Local builds without the variables produce an unsigned release APK.
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                signingConfig = signingConfigs.create("ciRelease") {
                    storeFile = file(keystoreFile)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    // JVM unit tests hit android.util.Log on the SecretsCipher decrypt-error
    // path; unmocked framework methods return defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Ktor Client & WebSockets
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager (background subscription refresh)
    implementation(libs.androidx.work.runtime.ktx)

    // LibSu (Root)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)

    // YAML & Coil
    implementation(libs.snakeyaml)
    implementation(libs.coil.compose)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}
