plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.songnotes.android.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // 28 is the floor the Macrobenchmark/BaselineProfileRule tooling
        // itself requires, independent of :app's own minSdk = 30.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Which module/variant this instrumentation test module measures.
    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Runs against whatever device is already connected via adb -- every other
// on-device verification in this repo already goes through a real physical
// device (see docs/handoff/PHASE-*.md), so a Gradle Managed Device adds
// infrastructure this project has had no need for anywhere else.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
