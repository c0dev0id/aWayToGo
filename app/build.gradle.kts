plugins {
    id("com.android.application")
}

// Injects the short git commit hash at build time so the version card in
// MapActivity can display the installed build and the update checker can
// compare it against GitHub releases.
fun getGitCommit(): String = try {
    val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .start()
    proc.inputStream.bufferedReader().readText().trim().also { proc.waitFor() }
} catch (_: Exception) { "unknown" }

android {
    namespace = "de.codevoid.aWayToGo"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.codevoid.aWayToGo"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "MAPTILER_KEY", "\"${System.getenv("MAPTILER_KEY") ?: ""}\"")
        buildConfigField("String", "GIT_COMMIT",   "\"${getGitCommit()}\"")
        buildConfigField("Long",   "BUILD_TIME",   "${System.currentTimeMillis()}L")

        // Only include device ABIs — drops x86/x86_64 emulator libs (~30–40 MB)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
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
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    // Core Android KTX (ContextCompat, ActivityCompat, etc.)
    implementation("androidx.core:core-ktx:1.15.0")

    // Activity (ComponentActivity base class)
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Lifecycle — provides lifecycleScope on ComponentActivity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // MapLibre
    implementation("org.maplibre.gl:android-sdk:11.0.0")
    // GeoJSON — declared explicitly because MapLibre uses `implementation` scope
    // (not `api`), so the library is not available at our compile time transitively.
    implementation("org.maplibre.gl:android-sdk-geojson:6.0.0")

    // OkHttp — MapLibre pulls this in transitively, but we declare it
    // explicitly because we configure the client directly via
    // HttpRequestUtil.setOkHttpClient() for disk caching and the tile gate.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
