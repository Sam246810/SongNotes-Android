// Pure-JVM module — no Android framework dependencies, no JNI. Per the
// plan's own module layout, :core:domain owns business logic like the
// clip/track mixing algorithm ("clipEngine") independent of :core:audio's
// engine/JNI concerns. Runs as plain JVM unit tests (no emulator/device
// needed), unlike everything in :core:audio, which has always needed a
// physical device or the NDK cross-compile workaround to verify anything.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Matches the other modules' sourceCompatibility/jvmTarget style rather
// than kotlin { jvmToolchain(17) } — the toolchain API triggers Gradle's
// auto-detection, which doesn't recognize this environment's JAVA_HOME
// (Android Studio's bundled JBR) as a usable JDK 17 toolchain candidate.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    testImplementation(libs.junit)
}
