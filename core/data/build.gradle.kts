// Android library, not pure-JVM like :core:domain -- Room + SQLCipher + Android
// Keystore all need the Android framework, unlike this module's original crypto-only
// content (Kdf.kt/Envelope.kt/AccountKeys.kt), which has zero android.* imports and
// still runs as plain JVM unit tests here, same as before the conversion. Matches
// the plan's own module layout, which names :core:data for "Room + SQLCipher,
// supabase-kt, crypto, sync engine" together, not split across modules.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.songnotes.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        // Room schema files aren't checked in yet -- no shipped release to migrate
        // FROM, so there's nothing for a migration test to diff against. Add
        // exportSchema/schemas-dir once a real migration is written.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))

    // Argon2id isn't in the JDK's own javax.crypto -- Bouncy Castle is the
    // standard, well-audited source for it on plain JVM/Android alike.
    implementation(libs.bouncycastle.provider)
    // The real org.json:json artifact -- used for the envelope JSON shape shared
    // with the desktop web app's crypto code (see Envelope.kt).
    implementation(libs.org.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)
    // SupportFactory below swaps Room's default SQLite driver for SQLCipher's --
    // same Room API surface (@Entity/@Dao/@Database), transparently encrypted
    // storage. This is what "SQLCipher, keyed by a random DB key" means in
    // practice: not a separate encryption layer bolted on top of Room, but the
    // SQLite implementation underneath it being an encrypting one.
    implementation(libs.sqlcipher.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
}
