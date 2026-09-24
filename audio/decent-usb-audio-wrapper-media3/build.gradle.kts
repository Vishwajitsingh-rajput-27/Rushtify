plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.decent.usbaudio.media3"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    // Mirror the app's build types so every app variant (including the
    // custom rawRelease) resolves a matching library variant.
    buildTypes {
        getByName("debug")
        getByName("release")
        create("rawRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }
}

dependencies {
    api(project(":audio:decent-usb-audio-driver"))
    implementation("androidx.core:core-ktx:1.18.0")
    // Pinned to the app's Media3 line to avoid binary mismatch.
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")
    implementation("androidx.media3:media3-database:1.2.1")

    // JSch fork (maintained) — SFTP streaming with native offset seek
    implementation("com.github.mwiede:jsch:0.2.23")
}
