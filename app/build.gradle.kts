import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Roborazzi only for store screenshots (`-Pscreenshots`), so normal, CI and
// F-Droid release builds never depend on it. It's on the classpath via the
// root build's `apply false`.
if (project.hasProperty("screenshots")) apply(plugin = "io.github.takahirom.roborazzi")

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
        // Plain literals on purpose: F-Droid reads these two lines with a regex
        // and never runs Gradle, so they must not be computed. Bump them with
        // `scripts/release.sh X.Y.Z` (also writes the changelog and the tag).
        // Code scheme: major*10000 + minor*100 + patch, so 1.1.2 is 10102.
        versionCode = 10202
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GitHub "owner/repo" whose Releases the in-app update check reads.
        // A constant (not an env var) so every release build is byte-identical.
        buildConfigField("String", "GITHUB_REPO", "\"AbhinavXT/debforge\"")
    }

    // F-Droid rejects the encrypted Google "dependency info" block in APKs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
        debug {
            // Handy for development: TMDB_API_KEY=... in local.properties
            // (never committed) gives debug builds posters out of the box.
            val tmdbKey = secret("TMDB_API_KEY", localProps, "TMDB_API_KEY").orEmpty()
            buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        }
        release {
            // Unsigned when no keystore is configured; CI and your machine
            // provide one via env vars or keystore.properties.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // Reproducible builds: F-Droid rebuilds from the tag and only
            // publishes our APK if theirs is identical. So nothing machine- or
            // secret-dependent goes into a release: no bundled TMDB key (users
            // add their own in Settings), no git metadata, no PNG re-crunching.
            buildConfigField("String", "TMDB_API_KEY", "\"\"")
            vcsInfo { include = false }
            isCrunchPngs = false
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
    testOptions {
        unitTests {
            // Robolectric (store screenshots) needs the merged resources.
            isIncludeAndroidResources = true
            all { test ->
                // The store-screenshot test boots Robolectric + Hilt and renders
                // the real UI: slow, and only useful when asked for with
                // `-Pscreenshots` (see .github/workflows/screenshots.yml).
                if (!project.hasProperty("screenshots")) test.exclude("**/screenshots/**")
            }
        }
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
    // Full icon set for the redesigned UI; R8 strips the unused ones from release.
    implementation(libs.androidx.compose.material.icons.extended)

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

    // In-app video player (PlayerView: controls, track pickers, TV D-pad).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    // Store screenshots on the JVM (app/src/test/.../screenshots)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
