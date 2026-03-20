import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.dokka)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
val externalLocalPropsFile =
    System
        .getenv("ZEROAI_LOCAL_PROPERTIES_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
        ?: rootProject.file("${System.getProperty("user.home")}\\.zeroai\\local.properties")
listOf(localPropsFile, externalLocalPropsFile)
    .distinctBy { it.absolutePath }
    .filter { it.exists() }
    .forEach { propsFile ->
        propsFile.inputStream().use(localProps::load)
    }

fun readBuildValue(
    envVar: String,
    fallbackProp: String,
): String? = System.getenv(envVar)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(fallbackProp)

/**
 * Reads a secret from Windows Credential Manager via the helper script.
 *
 * Falls back to [localProps] when the script is unavailable or the credential
 * is not stored, so CI and non-Windows environments still work.
 */
fun readCredential(
    target: String,
    fallbackProp: String,
): String? {
    val script = rootProject.file("scripts/read-credential.ps1")
    if (!script.exists()) return localProps.getProperty(fallbackProp)
    return try {
        val ps =
            listOf("pwsh", "powershell").firstOrNull { command ->
                runCatching {
                    ProcessBuilder(
                        command,
                        "-NoProfile",
                        "-Command",
                        "\$PSVersionTable.PSVersion.ToString()",
                    ).start()
                        .apply {
                            outputStream.close()
                        }.waitFor() == 0
                }.getOrDefault(false)
            } ?: return readBuildValue(target, fallbackProp)
        val proc =
            ProcessBuilder(
                ps,
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.absolutePath,
                "-Target",
                target,
            ).start()
        val output =
            proc.inputStream
                .bufferedReader()
                .readText()
                .trim()
        val exitCode = proc.waitFor()
        if (exitCode == 0 && output.isNotEmpty()) {
            output
        } else {
            readBuildValue(target, fallbackProp)
        }
    } catch (_: Exception) {
        readBuildValue(target, fallbackProp)
    }
}

android {
    namespace = "com.zeroclaw.android"
    compileSdk = 35

    val releaseStoreFile = readBuildValue("ZEROAI_RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
    val releaseKeyAlias = readBuildValue("ZEROAI_RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
    val hasReleaseSigning = releaseStoreFile != null && releaseKeyAlias != null

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = readCredential("ZeroAI_StorePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = releaseKeyAlias
                keyPassword = readCredential("ZeroAI_KeyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.zeroclaw.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 104
        versionName = "0.1.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now().format(DateTimeFormatter.ofPattern("MMM yyyy"))}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        managedDevices {
            devices {
                create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel7Api35") {
                    device = "Pixel 7"
                    apiLevel = 35
                    systemImageSource = "google"
                }
            }
            groups {
                create("ci") {
                    targetDevices.add(devices.getByName("pixel7Api35"))
                }
            }
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dokka {
    moduleName.set("ZeroAI Android")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
    }
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
        }
        perPackageOption {
            matchingRegex.set(".*\\.generated\\..*")
            suppress.set(true)
        }
    }
}

dependencies {
    implementation(project(":lib"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.wsc)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.security.crypto)
    implementation(libs.material)
    implementation(libs.profileinstaller)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher)
    implementation(libs.okhttp)
    implementation(libs.nanohttpd)
    implementation(libs.browser)
    implementation(libs.bouncycastle)
    implementation(libs.work.runtime.ktx)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.play.services.location)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.image.description)
    ksp(libs.room.compiler)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.room.testing)
}

detekt {
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    baseline = file("${rootProject.projectDir}/config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    allRules = false
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
