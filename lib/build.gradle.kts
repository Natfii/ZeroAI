plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.atomicfu)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
    `maven-publish`
}

android {
    namespace = "com.zeroclaw.lib"
    compileSdk = 35

    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

cargo {
    packageDirectory = file("../zeroclaw-android/zeroclaw-ffi")
}

uniffi {
    generateFromLibrary {
        packageName = "com.zeroclaw.ffi"
    }
}

dependencies {
    api(libs.jna) {
        artifact {
            type = "aar"
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.zeroclaw"
            artifactId = "zeroai-android"
            version = "0.2.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
    repositories {
        maven {
            name = "LocalRepository"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
