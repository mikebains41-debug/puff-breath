plugins {
    id("com.android.application")
}

// The API key comes from the environment (a GitHub Actions secret), never from the repo.
val smartSpectraApiKey = providers.environmentVariable("SMARTSPECTRA_API_KEY")
    .orElse(providers.gradleProperty("smartSpectraApiKey"))
    .orElse("")

fun String.asBuildConfigString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.gpuoptimizer.puffbreath"
    // SmartSpectra 3.x needs compileSdk 36.1 and minSdk 28.
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.gpuoptimizer.puffbreath"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "SMARTSPECTRA_API_KEY",
            "\"${smartSpectraApiKey.get().asBuildConfigString()}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.camera:camera-view:1.6.0")
    implementation("com.presagetech:smartspectra:3.4.0")
}
