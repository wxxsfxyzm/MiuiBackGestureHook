import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitCount = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim() }

val gitVersionCode = gitCommitCount.map { count ->
    count.toInt()
}

val localLspltAar = rootProject.file(
    "../LSPlt/build-android-arm64-v8a-16kb/lsplt-standalone-2.1-16kb.aar",
).also { aar ->
    require(aar.isFile) {
        "Missing locally rebuilt 16KB LSPlt AAR: ${aar.absolutePath}"
    }
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingProperty(name: String): String? =
    keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val localKeystoreFile = signingProperty("storeFile")
val localKeystorePassword = signingProperty("storePassword")
val localKeyAlias = signingProperty("keyAlias")
val localKeyPassword = signingProperty("keyPassword")
val hasLocalSigningConfig = listOf(
    localKeystoreFile,
    localKeystorePassword,
    localKeyAlias,
    localKeyPassword,
).all { it != null }

val envKeystoreFile = System.getenv("SIGNING_KEY")
val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
val envAlias = System.getenv("ALIAS")
val envKeyPassword = System.getenv("KEY_PASSWORD")
val hasEnvSigningConfig = listOf(
    envKeystoreFile,
    envKeystorePassword,
    envAlias,
    envKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.codex.miuibackgesturehook"
    compileSdk = 37
    ndkVersion = "30.0.16138531"

    buildFeatures {
        buildConfig = true
        compose = true
        prefab = true
    }

    defaultConfig {
        applicationId = "dev.codex.miuibackgesturehook"
        minSdk = 36
        targetSdk = 37
        versionCode = gitVersionCode.get()
        versionName = "0.11.8"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DLAUNCHER_PROFILE_INCLUDE_DIR=${rootProject.file("miui-home-hyos-native/generated").absolutePath}",
                )
                targets += "miui_home_hyos_lsp"
            }
        }
    }

    signingConfigs {
        create("release") {
            if (hasLocalSigningConfig) {
                storeFile = rootProject.file(localKeystoreFile!!)
                storePassword = localKeystorePassword
                keyAlias = localKeyAlias
                keyPassword = localKeyPassword
            } else if (hasEnvSigningConfig) {
                storeFile = file(envKeystoreFile!!)
                storePassword = envKeystorePassword
                keyAlias = envAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasLocalSigningConfig || hasEnvSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")

            if (hasLocalSigningConfig || hasEnvSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = rootProject.file("miui-home-hyos-native/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        jniLibs.excludes += "**/libandroidx.graphics.path.so"
        resources.merges += "META-INF/xposed/*"
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    compileOnly(project(":hidden-api"))

    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.miuix.blur.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.ui.android)
    implementation(files(localLspltAar))

    testImplementation(kotlin("test-junit"))
}
