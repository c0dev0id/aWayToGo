plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.codevoid.aWayToGo"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "de.codevoid.aWayToGo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(listOf("${rootDir}/rust/target/jniLibs"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

val buildRustLibs by tasks.registering(Exec::class) {
    val isRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    val extraArgs = if (isRelease) listOf("--release") else emptyList()

    inputs.dir("${rootDir}/rust/src")
    inputs.file("${rootDir}/rust/Cargo.toml")
    outputs.dir("${rootDir}/rust/target/jniLibs")

    commandLine(
        listOf(
            "cargo", "ndk",
            "--target", "arm64-v8a",
            "--target", "armeabi-v7a",
            "--target", "x86_64",
            "--output-dir", "${rootDir}/rust/target/jniLibs"
        ) + extraArgs + listOf("build")
    )
    workingDir("${rootDir}/rust")
}

tasks.named("preBuild") {
    dependsOn(buildRustLibs)
}

dependencies {
    implementation("com.google.android.material:material:1.11.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
