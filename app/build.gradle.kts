import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// --- Version from git ------------------------------------------------------
// versionName = latest tag without the leading "v" (v1.4.0 -> 1.4.0);
// versionCode = number of commits, so every build on main counts upward.
// Both fall back sensibly when git or tags are missing (e.g. a zip download).
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

val gitVersionName: String = git("describe", "--tags", "--abbrev=0", "--match", "v*")
    ?.removePrefix("v") ?: "1.0"
val gitVersionCode: Int = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

// --- Secrets: env var first (CI), then local.properties / keystore.properties
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(env: String, props: Properties, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: props.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = secret("DEBFORGE_KEYSTORE", keystoreProps, "storeFile")
val releaseStorePassword = secret("DEBFORGE_KEYSTORE_PASSWORD", keystoreProps, "storePassword")
val releaseKeyAlias = secret("DEBFORGE_KEY_ALIAS", keystoreProps, "keyAlias")
val releaseKeyPassword = secret("DEBFORGE_KEY_PASSWORD", keystoreProps, "keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it != null } && rootProject.file(releaseStoreFile!!).exists()

android {
    namespace = "com.abhinavxt.debforge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.abhinavxt.debforge"
        minSdk = 30
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional TMDB key for posters: TMDB_API_KEY env var (CI secret) or
        // local.properties (never committed). Users can also paste one in Settings.
        val tmdbKey = secret("TMDB_API_KEY", localProps, "TMDB_API_KEY").orEmpty()
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")

        // GitHub "owner/repo" whose Releases the in-app update check reads.
        // Override with DEBFORGE_GITHUB_REPO or GITHUB_REPO= in local.properties.
        val repo = secret("DEBFORGE_GITHUB_REPO", localProps, "GITHUB_REPO") ?: "abhinavxt/debforge"
        buildConfigField("String", "GITHUB_REPO", "\"$repo\"")
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
            // Unsigned when no keystore is configured; CI and your machine
            // provide one via env vars or keystore.properties.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle / Navigation for Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Images (posters)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
