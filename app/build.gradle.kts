import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ndkDir = File(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "", "ndk/27.0.12077973")
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { it != null }

android {
    namespace = "dev.voftec.portal"
    compileSdk = 34
    ndkPath = ndkDir.absolutePath

    defaultConfig {
        applicationId = "dev.voftec.portal"
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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
