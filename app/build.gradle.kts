import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.bhavya.music"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    val localProps = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    setProperty(parts[0].trim(), parts[1].trim())
                }
            }
        }
    }

    fun resolveSecret(vararg keys: String): String {
        for (key in keys) {
            val fromEnv = System.getenv(key)
            if (!fromEnv.isNullOrBlank()) return fromEnv.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val fromGradle = project.findProperty(key) as? String
            if (!fromGradle.isNullOrBlank()) return fromGradle.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val fromLocal = localProps.getProperty(key)
            if (!fromLocal.isNullOrBlank()) return fromLocal.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val dotEnv = rootProject.file(".env")
            if (dotEnv.isFile) {
                dotEnv.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$key=")) {
                            val v = trimmed.substringAfter("=").trim().replace("\"", "").replace("\\", "")
                            if (v.isNotBlank()) return v
                        }
                    }
                }
            }
        }
        return ""
    }

defaultConfig {
        applicationId = "com.bhavya.music"
        minSdk = (project.findProperty("minSdk") as? String)?.toIntOrNull() ?: 29
        targetSdk = 35
        versionCode = 30
        versionName = "1.8"

        // Native secrets (addon client lock) live strictly in native .so via
        // SecretsBridge_generated.h (tools/generate_native_secrets.py).
        // No secret fields are exposed in DEX / BuildConfig.

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // Android 15+ can boot with 16 KB memory pages; all native
                    // libraries must be built/aligned accordingly. Ignored
                    // harmlessly by NDK toolchains that predate the flag.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                )
                
                
            }
        }
    }

    signingConfigs {
        create("release_config") {
            val base64Key = resolveSecret("SIGNING_KEY")
            val storeFilePath = resolveSecret("RELEASE_STORE_FILE")
            val storePasswordProp = resolveSecret("RELEASE_STORE_PASSWORD", "KEY_STORE_PASSWORD")
            val keyAliasProp = resolveSecret("RELEASE_KEY_ALIAS", "ALIAS").ifBlank { "release_key" }
            val keyPasswordProp = resolveSecret("RELEASE_KEY_PASSWORD", "KEY_PASSWORD").ifBlank { storePasswordProp }

            val keystoreFile: File? = when {
                base64Key.isNotBlank() -> {
                    try {
                        val decodedBytes = Base64.getDecoder().decode(base64Key.trim())
                        val tempKeystore = layout.buildDirectory.file("signing/release.keystore").get().asFile
                        tempKeystore.parentFile.mkdirs()
                        tempKeystore.writeBytes(decodedBytes)
                        tempKeystore
                    } catch (_: Exception) {
                        null
                    }
                }
                storeFilePath.isNotBlank() -> file(storeFilePath)
                else -> null
            }

            if (keystoreFile != null && keystoreFile.exists() && storePasswordProp.isNotBlank()) {
                storeFile = keystoreFile
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release_config")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("rawRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // Raw variant -- no code/resource shrinking, no ProGuard/R8
            signingConfig = signingConfigs.getByName("release_config")
            // proguardFiles from initWith are ignored when minify is off
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by org.jellyfin.media3:media3-ffmpeg-decoder AAR metadata.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        ignoreWarnings = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.palette)

    // Home-screen "Now Playing" widget (Glance -- Compose-style APIs over
    // RemoteViews), driven by the same MediaController access the local
    // scrobbler (MediaScrobbleListenerService) already holds.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Required even in a Compose-only app: Theme.Material3.DayNight.NoActionBar
    // (used as the AndroidManifest/splash theme parent in themes.xml) is an XML
    // style resource shipped by this artifact. androidx.compose.material3 is
    // Compose-only Kotlin and contributes no AAPT-resolvable style/ resources,
    // so without this dependency that parent can never be found by the linker.
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.lyrics.ui)
    implementation(libs.lyrics.core)
    // Installs the baseline profiles bundled inside Compose (and other
    // androidx) AARs so hot UI paths are AOT-compiled on device instead of
    // running through JIT on first use -- a large, zero-code smoothness win
    // for scrolling and animations in release builds.
    implementation(libs.androidx.profileinstaller)

    // Native in-app audio playback, background service, system media
    // controls, Bluetooth/headset controls and a MediaController-backed UI.
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    // Segmented provider-module path: DASH chunk source + CDM decryption.
    // Pinned to the same 1.2.1 line as exoplayer/hls to avoid binary mismatch.
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    // MediaBrowserServiceCompat/MediaSessionCompat bridge used by Android
    // Auto to browse the Bhavya library and control the same player.
    implementation("androidx.media:media:1.7.0")

    // GPLv3 Media3-matched FFmpeg software decoder (distribution must comply).
    // The renderer factory prefers FFmpeg for every codec it supports so all
    // devices decode through one deterministic, OEM-bug-free path; platform
    // decoders remain as automatic fallbacks.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1")

    // Core library desugaring required by the FFmpeg decoder AAR metadata.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Low-latency native output. Version 1.10 remains API-compatible with the
    // requested Oboe 1.8+ baseline and exposes its CMake target through Prefab.
    implementation("com.google.oboe:oboe:1.10.0")

    // Metadata/search remains local InnerTube/NewPipe functionality; playback
    // resolves through InnerTubeX first and retains NewPipe as a fallback.
    implementation(libs.newpipe.extractor)

    // InnerTubeX is invoked through the compatibility adapter because its
    // current release is built with a newer Kotlin metadata version than the
    // app. Runtime-only keeps the app compiler on its existing Kotlin line.
    runtimeOnly(libs.innertubex)
    runtimeOnly("io.ktor:ktor-client-cio:3.5.2")

    // Unit Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Bit-perfect USB exclusive output: audio_engine UsbAudioDriver.
    implementation(project(":audio:decent-usb-audio-driver"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(libs.versions.kotlin.get())
        }
    }
}

tasks.withType<Test> {
    maxHeapSize = "2048m"
}

// Generate native secrets header before CMake configures.
// CI provides ADDON_CLIENT_SECRET / RELEASE_CERT_SHA256 via env/secrets.
val nativeSecretsPlaceholder = """
    |// AUTO-GENERATED PLACEHOLDER -- NOT FOR PRODUCTION, local dev build only.
    |// This file is gitignored. CI generates the real version via tools/generate_native_secrets.py.
    |// This stub mirrors the #else fallback in SecretsBridge.cpp.
    |#pragma once
    |#include <cstdint>
    |#include <cstddef>
    |
    |constexpr char EXPECTED_CERT_PREFIX[] = "PLACEHOLDER_REPLACE_WITH_YOUR_CERT_SHA256";
    |
    |static const uint8_t CLIENT_SECRET_SALT[16] = {0};
    |static constexpr size_t CLIENT_SECRET_TOTAL_LEN = 0;
    |static constexpr int CLIENT_SECRET_FRAGMENT_COUNT = 0;
    |
    |struct Fragment {
    |    const uint8_t* data;
    |    uint8_t        len;
    |    uint8_t        mask;
    |};
    |
    |static const Fragment CLIENT_SECRET_FRAGMENTS[] = {};
    |
""".trimMargin()

val generateNativeSecrets by tasks.registering {
    group = "build"
    description = "Generates the native addon-lock secrets header consumed by SecretsBridge.cpp."

    val headerFile = layout.projectDirectory.file("src/main/cpp/SecretsBridge_generated.h").asFile
    val rootDir = rootProject.projectDir
    val scriptFile = rootProject.file("tools/generate_native_secrets.py")
    val dotEnvFile = rootProject.file(".env")
    val placeholder = nativeSecretsPlaceholder
    val interpreters =
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
            listOf("python", "python3", "py")
        } else {
            listOf("python3", "python")
        }

    inputs.file(scriptFile)
    // .env is developer-local and gitignored, so it is absent on CI. Gradle 9 still
    // fails input validation for a missing inputs.file(...) target even with
    // .optional(), which broke every release build with "specifies file '.env'
    // which doesn't exist". Track presence instead: when the file exists it is a
    // real input, and when it does not only the flag below is recorded.
    val dotEnvPresent = dotEnvFile.isFile
    inputs.property("dotEnvPresent", dotEnvPresent)
    if (dotEnvPresent) {
        inputs.file(dotEnvFile).withPropertyName("dotEnv")
    }
    outputs.file(headerFile)

    doLast {
        // A header is already present (generated here or by a previous run):
        // leave it alone so a build never destroys a real keystore-bound secret.
        if (headerFile.isFile && headerFile.length() > 0L) {
            logger.lifecycle("generateNativeSecrets: reusing existing ${headerFile.name}")
            return@doLast
        }

        // No Python on the host is the common case on developer machines, and a
        // failed *spawn* is not covered by isIgnoreExitValue -- it aborts the whole
        // build. Probe each interpreter and fall back to the inert stub instead.
        var generated = false
        for (interpreter in interpreters) {
            try {
                val process = ProcessBuilder(interpreter, scriptFile.absolutePath)
                    .directory(rootDir)
                    .redirectErrorStream(true)
                    .apply { environment()["PYTHONIOENCODING"] = "utf-8" }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitValue = process.waitFor()
                if (exitValue == 0 && headerFile.isFile && headerFile.length() > 0L) {
                    logger.lifecycle("generateNativeSecrets: generated via '$interpreter'")
                    generated = true
                    break
                }
                logger.info(
                    "generateNativeSecrets: '$interpreter' exited $exitValue" +
                        if (output.isBlank()) "" else " ($output)"
                )
            } catch (e: Exception) {
                logger.info("generateNativeSecrets: interpreter '$interpreter' unavailable (${e.message})")
            }
        }

        if (!generated) {
            logger.warn(
                "generateNativeSecrets: no usable Python interpreter found; writing the inert " +
                    "placeholder header. The addon client lock stays empty -- set ADDON_CLIENT_SECRET " +
                    "and run tools/generate_native_secrets.py to enable it."
            )
            headerFile.parentFile.mkdirs()
            headerFile.writeText(placeholder)
        }
    }
}
tasks.matching { it.name.startsWith("preBuild") || it.name.startsWith("configureCMake") }.configureEach {
    dependsOn(generateNativeSecrets)
}

