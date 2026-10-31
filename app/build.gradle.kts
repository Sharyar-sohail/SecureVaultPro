import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// The signing key must stay the same key across every release — the owner upgrades installs over
// the old one — but its passwords no longer live in this file, which ships in the source and in
// every backup. They come from keystore.properties at the project root (listed in .gitignore) or,
// for a machine with no such file, from the SVP_KEYSTORE_* / SVP_KEY_* environment variables.
// Without either, a release is built unsigned and says so loudly rather than quietly reusing a
// published secret.
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String =
    signingProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: ""

val keystorePath = signingValue("storeFile", "SVP_KEYSTORE_FILE").ifBlank { "release.jks" }
val keystorePassword = signingValue("storePassword", "SVP_KEYSTORE_PASSWORD")
val keyAliasName = signingValue("keyAlias", "SVP_KEY_ALIAS")
val keyPasswordValue = signingValue("keyPassword", "SVP_KEY_PASSWORD").ifBlank { keystorePassword }
val canSignRelease = keystorePassword.isNotBlank() && keyAliasName.isNotBlank() &&
    rootProject.file(keystorePath).isFile

if (!canSignRelease) {
    logger.warn(
        "SecureVault: no signing credentials (keystore.properties or SVP_KEYSTORE_* env). " +
            "assembleRelease will produce an UNSIGNED apk."
    )
}

android {
    namespace = "com.securevault.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.securevault.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 363
        versionName = "3.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "DRIVE_APP_FOLDER", "\"SecureWalletPro\"")
        buildConfigField("String", "DEFAULT_COMPANION_URL", "\"https://securevaultpro.blogspot.com\"")
    }

    signingConfigs {
        create("release") {
            if (canSignRelease) {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isDebuggable = false
            // Shrinking stays off for the build the owner installs. Every fault so far has
            // appeared only in the installed release, never in the source it was built from,
            // and with no debug bridge on this phone a rewritten or stripped class is
            // undiagnosable: the stack the owner reads is the only instrument, and R8 takes
            // its name out of it. The cost is APK size; the gain is that the shipped binary
            // behaves exactly like the code under review and its reports read plainly.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/native-image/**"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.datastore)

    // Passkey/FIDO2. Pinned to what is already in the offline Gradle cache — the release build runs
    // with `--offline`, so an artifact that is not cached here cannot be introduced at all.
    // `credentials-play-services-auth:1.2.0-rc01` is NOT shippable: only its AAR is cached, its
    // descriptor and its Play Services transitives are not, so `--offline` resolution fails on it.
    implementation("androidx.credentials:credentials:1.2.0-rc01")


    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.play.services.auth)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive) {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation(libs.google.http.client.gson)
    implementation(libs.gson)

    // Realtime sync backend. Initialisation stays lazy inside the firebase package: the
    // generated Firebase init provider is removed from the manifest so no Firebase class
    // is touched while the app process is still coming up.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.accompanist.permissions)
    implementation("com.google.zxing:core:3.5.3")

    // secp256k1 and Keccak-256 for the wallet's key import path. Verified against published test
    // vectors in crypto/EvmCryptoVectorTest, and pinned equal to the in-house codec so there is
    // exactly one answer for "what address does this key give".
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}
