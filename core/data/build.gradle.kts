import java.util.Properties

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
    alias(libs.plugins.kotlin.serialization)
}

// Same Supabase project as the desktop web app's .env.local (VITE_SUPABASE_URL /
// VITE_SUPABASE_ANON_KEY) -- read from local.properties (gitignored), never
// committed, matching the web app's own gitignored-local-file convention. Falls
// back to empty strings (not a build failure) so the rest of the app still
// builds/runs for anyone who hasn't set these up yet -- Phase 7's Supabase
// features simply won't have anything to talk to until they are.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val supabaseUrl: String = localProperties.getProperty("supabase.url", "")
val supabaseAnonKey: String = localProperties.getProperty("supabase.anonKey", "")

android {
    namespace = "com.songnotes.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildFeatures {
        buildConfig = true
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

    // Auth + Postgrest against the SAME Supabase project/schema the desktop web
    // app already uses (see docs/handoff/PHASE-07.md) -- email+password, matching
    // the web app's existing auth exactly rather than introducing Google Sign-In/
    // Credential Manager, which would need external OAuth infra this repo has no
    // way to set up. ktor-client-okhttp is the HTTP engine supabase-kt needs on
    // Android; kotlinx-serialization is how Postgrest (de)serializes rows.
    //
    // `api`, not `implementation`, for the supabase-kt pieces: SupabaseAuthRepository's
    // own public constructor takes a `SupabaseClient` default parameter, so :app
    // needs that type on its compile classpath too, not just this module's own.
    api(platform(libs.supabase.bom))
    api(libs.supabase.postgrest)
    api(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    api(libs.kotlinx.serialization.json)

    // Outbox push + incremental pull run as a WorkManager CoroutineWorker --
    // lives here, not :app, since it's part of the "sync engine" the plan's own
    // module layout puts in :core:data alongside Room/SQLCipher/crypto.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
}
