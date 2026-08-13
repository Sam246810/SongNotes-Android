# Phase 11 hardening: R8 code shrinking/obfuscation for the release build.
# See docs/handoff/PHASE-11.md for the reasoning behind each block below and
# what was actually exercised on-device to verify it.

# --- JNI (core:audio's native bridge) ---------------------------------------
# AudioEngine and Calibration bind to their C++ implementations (jni_bridge.cpp,
# calibration_jni.cpp) via classic implicit JNI name mangling --
# Java_com_songnotes_core_audio_AudioEngine_nativeCreate and friends -- NOT
# RegisterNatives. If R8 renames either class or any `nativeXxx` method, the
# native side's hardcoded exported symbol name stops matching and every native
# call fails with UnsatisfiedLinkError at runtime. proguard-android-optimize.txt
# already keeps classes with native methods for exactly this reason; this rule
# is kept here explicitly (redundant with the default, deliberately) since a
# broken native binding takes down the entire app, not just one feature.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- kotlinx.serialization (Supabase auth/song sync payloads) ---------------
# SongRow/UserKeysRow (SupabaseSongsAdapter.kt/SupabaseAuthRepository.kt) rely
# on a compiler-generated $serializer companion looked up by reflection at
# runtime. This is kotlinx.serialization's own documented required proguard
# block, scoped to this app's package so any future @Serializable class is
# covered automatically without editing this file again.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.songnotes.** {
    *** Companion;
}
-keepclasseswithmembers class com.songnotes.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.songnotes.**$$serializer { *; }

# --- WorkManager (SongSyncWorker) --------------------------------------------
# WorkManager's default WorkerFactory instantiates workers by class name via
# reflection (Class.forName + the (Context, WorkerParameters) constructor) --
# renaming/removing either breaks background sync with no compile-time signal.
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- BouncyCastle (Argon2id key derivation, core:data) -----------------------
# A plain JAR (no consumer proguard rules of its own, unlike the AAR deps
# below). Its JCE-style provider classes are looked up by the crypto
# framework via registered algorithm names, not direct reference, so R8's
# reachability analysis can't see the real usage -- keeping the whole package
# is the standard, safe approach for this library.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- SQLCipher (encrypted Room database) -------------------------------------
# Defensive: SQLCipher wraps its own native SQLite build via JNI. Getting this
# wrong doesn't corrupt data (the database on disk is untouched) but does mean
# the app can no longer open it, which is just as bad from the user's side.
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# --- Ktor / supabase-kt --------------------------------------------------
# Kotlin Multiplatform libraries; the Android target doesn't use every engine
# class (Darwin/JS/etc.), which shows up as "missing class" notes rather than
# real problems. Silenced so they don't get promoted to build-breaking errors.
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**
