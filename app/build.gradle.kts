import java.net.HttpURLConnection
import java.security.MessageDigest
import java.net.URL
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    id("jacoco")
}

// Mihomo core is kept out of git and downloaded at build time.
// Pinned version + sha256 live in gradle/mihomo-core.properties.
val coreProperties = Properties().apply {
    val file = rootProject.file("gradle/mihomo-core.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val coreVersion = coreProperties.getProperty("version") ?: "core-v1.19.33-mihomet.3"
val coreCacheDir = rootProject.layout.projectDirectory.dir(".mihomo-core-cache")

val downloadMihomoCore by tasks.registering {
    group = "build"
    description = "Downloads the pinned mihomo core binary and geo databases into the app module"
    doLast {
        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        fun download(url: String, dest: File, expectedSha: String?, minSize: Long = 1_000_000) {
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
            } else if (dest.length() < minSize) {
                dest.delete()
                throw GradleException("Downloaded ${dest.name} looks truncated (${dest.length()} bytes).")
            }
        }

        // Core binary: Mihomet patched core (mihomo v1.19.33 + non-root TUN fix,
        // see the mihomet/core repository: patch + reproducible build script).
        // Published as raw (non-gzip) libmihomo-<abi>.so on the mihomet GitHub release,
        // verified by sha256 of the .so itself.
        listOf("arm64-v8a", "armeabi-v7a", "x86_64").forEach { abi ->
            val target = layout.projectDirectory.file("src/main/jniLibs/$abi/libmihomo.so").asFile
            val expectedSize = coreProperties.getProperty("size.$abi")?.toLongOrNull()
            if (target.isFile && expectedSize != null && target.length() == expectedSize) {
                return@forEach
            }
            val so = coreCacheDir.file("libmihomo-$abi-$coreVersion.so").asFile
            val expectedSha = coreProperties.getProperty("checksum.$abi")
                ?: throw GradleException("gradle/mihomo-core.properties is missing checksum.$abi")
            if (!so.isFile || !sha256(so).equals(expectedSha, ignoreCase = true)) {
                download(
                    "https://github.com/mihomet/core/releases/download/$coreVersion/libmihomo-$abi.so",
                    so,
                    expectedSha
                )
            }
            target.parentFile.mkdirs()
            so.copyTo(target, overwrite = true)
            logger.lifecycle("Mihomet: installed libmihomo.so ($abi) from core $coreVersion")
        }

        // Geo databases drive ROUTING decisions (a poisoned geosite steers bank/work
        // domains through the proxy), so every download is verified against the
        // .sha256sum published in the SAME MetaCubeX release: download corruption is
        // caught, while upstream republishes stay self-healing (fresh file + fresh
        // checksum travel together; trusting MetaCubeX upstream is the same trust
        // assumption as the rest of the build).
        mapOf("geoip.metadb" to "geoip", "geosite.dat" to "geosite").forEach { (fileName, _) ->
            val target = layout.projectDirectory.file("src/main/assets/$fileName").asFile
            if (target.isFile && target.length() > 1_000_000) {
                return@forEach
            }
            val cached = coreCacheDir.file(fileName).asFile
            if (!cached.isFile || cached.length() < 1_000_000) {
                val geoBase = "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download"
                val sumFile = coreCacheDir.file("$fileName.sha256sum").asFile
                download("$geoBase/$fileName.sha256sum", sumFile, null, minSize = 16)
                val published = sumFile.readText().trim().split(Regex("\\s+")).first()
                download("$geoBase/$fileName", cached, published)
            }
            target.parentFile.mkdirs()
            cached.copyTo(target, overwrite = true)
            logger.lifecycle("Mihomet: installed assets/$fileName (checksum verified)")
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadMihomoCore) }

android {
    namespace = "org.mihomet.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.mihomet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.3.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABIs are governed by splits.abi below (ndk.abiFilters is illegal
        // alongside it). The three ABIs must stay in sync with
        // downloadMihomoCore, which fetches a libmihomo.so per ABI.
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
            // Local builds without the variables produce an UNSIGNED release APK
            // (never silently debug-signed — debug keys are public knowledge).
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                signingConfig = signingConfigs.create("ciRelease") {
                    storeFile = file(keystoreFile)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
            // else: no signingConfig -> app-release-unsigned.apk
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
        // M3 Expressive components (material3 1.5.0-beta02) are gated behind the
        // experimental opt-in; the app adopts them app-wide (MD3E redesign).
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Audit P2.10: one APK per ABI instead of a ~72 MB fat APK — the bundled
    // mihomo core is tens of MB per ABI, so a universal APK pays that cost
    // three times. downloadMihomoCore still installs all three .so into
    // jniLibs at preBuild; the split only changes packaging. Version codes get
    // the default per-ABI offset (versionCode * 1000 + abi index) from AGP.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // JVM unit tests hit android.util.Log on the SecretsCipher decrypt-error
    // path; unmocked framework methods return defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Audit P2.7: lint runs in CI and FAILS on new issues only — every issue
    // present at adoption time is frozen in lint-baseline.xml (18 errors /
    // 89 warnings at adoption, see the file for the breakdown). Regenerate
    // with `./gradlew :app:updateLintBaseline` when debt is actually fixed.
    lint {
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Room migration tests (audit P1.3) run with MigrationTestHelper over the
    // exported schemas — expose app/schemas as androidTest assets.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Audit P2.7: minimal detekt config (sizes, unused imports, exceptions).
// Pre-existing violations are frozen in the baseline; only new ones fail.
detekt {
    config.setFrom(files("$rootDir/config/detekt.yml"))
    baseline = file("$rootDir/config/detekt-baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
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
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.material.kolor)
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

    // QR codes (subscription sharing)
    implementation(libs.zxing.core)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.tooling)
}

// Unit-test coverage report (CI uploads it as an artifact; no hard gate — the
// Android-bound service/UI layers are covered by instrumented tests instead).
jacoco {
    toolVersion = "0.8.12"
}
tasks.register("jacocoUnitTestReport", JacocoReport::class) {
    dependsOn("testDebugUnitTest")
    group = "verification"
    outputs.upToDateWhen { false }
    executionData(fileTree(layout.buildDirectory.dir("jacoco")) { include("**/*.exec") })
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(
                "**/*Test*.*",
                "**/di/**",
                "**/*_Factory*.*",
                "**/*\$Injector*.*",
                "**/R.class", "**/R\$*.class", "**/BuildConfig.*",
                "**/*_Impl\$*.*", "**/*\$serializer*.*",
                "dagger/**", "hilt_aggregated_deps/**"
            )
        }
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
    }
}
