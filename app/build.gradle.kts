import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ndkDir = File(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "", "ndk/27.0.12077973")

android {
    namespace = "dev.voftec.airplaytv"
    compileSdk = 34
    ndkPath = ndkDir.absolutePath

    defaultConfig {
        applicationId = "dev.voftec.airplaytv"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                abiFilters("armeabi-v7a", "arm64-v8a")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Native deps (OpenSSL, libplist sources) are built by ./native/fetch-and-build-deps.sh
    // which Gradle invokes automatically before configuring CMake.
    tasks.named("preBuild") {
        dependsOn("buildNativeDeps")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

tasks.register("buildNativeDeps") {
    description = "Fetches and builds OpenSSL + unpacks libplist sources (idempotent)"
    group = "build"
    onlyIf {
        !File(rootDir, "native/prebuilt/arm64-v8a/lib/libcrypto.a").exists() ||
            !File(rootDir, "native/prebuilt/armeabi-v7a/lib/libcrypto.a").exists() ||
            !File(rootDir, "native/deps/libplist-2.7.0/src/plist.c").exists()
    }
    doLast {
        exec {
            workingDir = rootDir
            commandLine("bash", "native/fetch-and-build-deps.sh")
            environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "")
        }
    }
}
