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
)

// Check at execution time so configuration-only tasks (sync, clean, tasks) still work
// without the sibling LSPlt checkout; every build still fails before compiling.
val verifyLocalLsplt by tasks.registering {
    val aar = localLspltAar
    doLast {
        require(aar.isFile) {
            "Missing locally rebuilt 16KB LSPlt AAR: ${aar.absolutePath}"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyLocalLsplt)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// The Android 16 Java MiuiHome is embedded as an extracted native library so PackageManager can
// parse it from the module's own lib directory. It is a proprietary Xiaomi binary: supply a local
// path through -PjavaHomeLauncherApk or local.properties; it never enters source control.
val javaHomeLauncherApk = ((findProperty("javaHomeLauncherApk") as String?)
    ?: localProperties.getProperty("javaHomeLauncherApk"))?.takeIf { it.isNotBlank() }
// Builds without the local APK keep the standard in-APK native packaging unchanged.
val embedJavaHomeLauncher = javaHomeLauncherApk != null
val javaHomeJniLibsDir = layout.buildDirectory.dir("generated/javaHomeJniLibs")
val stageJavaHomeLauncher by tasks.registering(Sync::class) {
    into(javaHomeJniLibsDir)
    if (javaHomeLauncherApk != null) {
        val apk = file(javaHomeLauncherApk)
        doFirst {
            require(apk.isFile) { "Missing Java MiuiHome APK: ${apk.absolutePath}" }
        }
        from(apk) {
            into("arm64-v8a")
            rename { "libjavahome_launcher.so" }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(stageJavaHomeLauncher)
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
        versionName = "0.14.0"

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

    if (embedJavaHomeLauncher) {
        sourceSets["main"].jniLibs.srcDir(javaHomeJniLibsDir.get().asFile)

        // Extracted packaging compresses native libraries; LSPosed loads the native entry and
        // DexKit straight from the APK, so keep them stored (page alignment follows below).
        androidResources {
            noCompress += "so"
        }
    }

    packaging {
        // Extracted only to place the embedded Java MiuiHome on disk as a world-readable file.
        jniLibs.useLegacyPackaging = embedJavaHomeLauncher
        if (embedJavaHomeLauncher) {
            jniLibs.keepDebugSymbols += "**/libjavahome_launcher.so"
        }
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
    implementation(libs.miuix.glass.android)
    implementation(libs.miuix.glass.icons.android)
    implementation(libs.miuix.icons.android)
    implementation(libs.miuix.preference.android)
    implementation(libs.miuix.ui.android)
    implementation(files(localLspltAar))

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.libxposed.api)
}

// AGP aligns stored libraries to 4 bytes when packaging for extraction. Re-align them to 16 KiB
// pages for LSPosed's in-APK loading of the native entry, then re-sign with the same key.
// Values are resolved at configuration time so the task action stays configuration-cache safe.
val realignSigning = android.signingConfigs.getByName("release")
val realignStoreFile: String? = realignSigning.storeFile?.absolutePath
val realignStorePassword: String? = realignSigning.storePassword
val realignKeyAlias: String? = realignSigning.keyAlias
val realignKeyPassword: String? = realignSigning.keyPassword
val realignBuildTools: String = androidComponents.sdkComponents.sdkDirectory.get().asFile
    .resolve("build-tools/${android.buildToolsVersion}").absolutePath

if (embedJavaHomeLauncher) tasks.matching {
    it.name == "packageDebug" || it.name == "packageRelease"
}.configureEach {
    val storeFile = realignStoreFile
    val storePassword = realignStorePassword
    val keyAlias = realignKeyAlias
    val keyPassword = realignKeyPassword
    val buildTools = realignBuildTools
    doLast {
        // Skipping the re-alignment would leave the native entry unloadable from the APK.
        check(storeFile != null && keyAlias != null) {
            "Embedding the Java MiuiHome requires the release signing configuration " +
                "to re-align and re-sign the APK."
        }
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "${command[0]} failed: $output" }
        }
        outputs.files.asFileTree.matching { include("*.apk") }.files.forEach { apk ->
            val aligned = File(apk.parentFile, apk.name + ".aligned")
            run("$buildTools/zipalign", "-f", "-P", "16", "4", apk.path, aligned.path)
            run("$buildTools/apksigner", "sign", "--ks", storeFile,
                "--ks-pass", "pass:$storePassword", "--ks-key-alias", keyAlias,
                "--key-pass", "pass:$keyPassword", "--out", apk.path, aligned.path)
            aligned.delete()
            File(apk.parentFile, apk.name + ".idsig").delete()
        }
    }
}
