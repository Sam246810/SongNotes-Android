// Pure-JVM module, same reasoning as :core:domain's own header comment: the
// account-key envelope crypto here (Argon2id KDF, AES-GCM wrap/unwrap, JSON
// (de)serialization) is math, not platform integration -- runs as plain JVM
// unit tests, no emulator/device needed. Per docs/PLAN.md's own "deliberate
// slack" note, Phase 6 doesn't have to depend on a device for this piece.
// Room + SQLCipher + Keystore (also nominally "Phase 6", per the plan's module
// layout putting them in :core:data too) DO need an Android context and
// instrumented tests -- that's a deliberately separate, later addition to this
// module, not implied by anything here.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    // Argon2id isn't in the JDK's own javax.crypto -- Bouncy Castle is the
    // standard, well-audited source for it on plain JVM/Android alike.
    implementation(libs.bouncycastle.provider)
    // The real org.json:json artifact (not Android's stub-only bundled copy,
    // which throws in plain JVM unit tests) -- matches the JSON shape already
    // used by :app's SongStorage.kt, since this envelope eventually gets
    // wired into that same app.
    implementation(libs.org.json)
    testImplementation(libs.junit)
}
