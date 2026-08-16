import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.songnotes.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.songnotes.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-phase0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // TEMPORARY placeholder so a release-configured build can be
            // installed and verified on-device at all (an unsigned release
            // APK can't be adb-installed). Phase 11 replaces this with a
            // real release signingConfig -- see
            // docs/handoff/PHASE-11-prep-navigation.md.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // bcprov-jdk18on (Argon2id, via :core:data) and jspecify both ship a
            // multi-release-JAR stub at this exact path -- functionally identical
            // no-op manifests, safe to just pick one rather than fail the merge.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core:audio"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Phase 13: collectAsStateWithLifecycle for SyncStatus (was only on the
    // classpath transitively via compose-ui before this -- made explicit
    // since Phase 13 is the first real dependency on it).
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.profileinstaller)

    debugImplementation(libs.androidx.ui.tooling)

    baselineProfile(project(":baselineprofile"))
}

// docs/PLAN.md's "Module layout" section called this out from day one: "Every
// `.so` must build with `-Wl,-z,max-page-size=16384`... add Google's
// `check_elf_alignment.sh` to CI on day one — SQLCipher's prebuilt is the
// most likely to bite." Phase 11 never actually added it. This is the
// equivalent check, run against the debug APK's packaged .so files.
tasks.register("checkElfAlignment") {
    group = "verification"
    description = "Fails if any packaged native library isn't 16 KB page-size aligned " +
        "(mandatory on Android 15+ devices with strict enforcement), except for the " +
        "known-upstream-gap allowlist below."
    dependsOn("assembleDebug")

    // Prebuilt .so files this project doesn't build from source and can't fix directly.
    // Confirmed misaligned via llvm-readelf on 2026-08-15 -- see
    // docs/handoff/PHASE-11.md's "What's NOT done". Drop an entry here once its upstream
    // ships a 16 KB-aligned build (re-run this task to confirm before removing).
    val knownMisaligned = setOf("libsqlcipher.so")
    val requiredAlignment = 0x4000L // 16 KB

    val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    inputs.file(apkFile)

    doLast {
        val sdkDir = run {
            val localProps = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            localProps.getProperty("sdk.dir")
                ?: System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: error("Can't locate the Android SDK: no sdk.dir in local.properties and no ANDROID_HOME/ANDROID_SDK_ROOT env var")
        }
        // Matches core/audio/build.gradle.kts's pinned ndkVersion -- any reasonably
        // recent NDK's llvm-readelf reads ELF headers fine regardless of version, this
        // just avoids requiring a second NDK install for a check that doesn't need one.
        val ndkVersion = "27.2.12479018"
        val osName = System.getProperty("os.name").lowercase()
        val hostTag = when {
            osName.contains("win") -> "windows-x86_64"
            osName.contains("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val exeSuffix = if (osName.contains("win")) ".exe" else ""
        val readelf = File("$sdkDir/ndk/$ndkVersion/toolchains/llvm/prebuilt/$hostTag/bin/llvm-readelf$exeSuffix")
        if (!readelf.exists()) error("llvm-readelf not found at $readelf -- is NDK $ndkVersion installed?")

        val extractDir = layout.buildDirectory.dir("elfAlignmentCheck").get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }

        // 16 KB alignment is only meaningful for 64-bit ABIs -- 32-bit devices never use
        // >4 KB pages, so armeabi-v7a's prebuilts (e.g. the NDK's own libc++_shared.so)
        // are legitimately 4 KB-aligned and not a bug. Matches what the on-device
        // "Android App Compatibility" warning itself checks (it only ever names
        // lib/arm64-v8a/... paths).
        val sixtyFourBitAbis = setOf("arm64-v8a", "x86_64")

        val misalignedNow = mutableSetOf<String>()
        ZipFile(apkFile.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    entry.name.startsWith("lib/") && entry.name.endsWith(".so") &&
                        sixtyFourBitAbis.any { abi -> entry.name.startsWith("lib/$abi/") }
                }
                .forEach { entry ->
                    val libName = entry.name.substringAfterLast('/')
                    val outFile = File(extractDir, "${entry.name.replace('/', '_')}")
                    zip.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }

                    val stdout = ByteArrayOutputStream()
                    project.exec {
                        commandLine(readelf.absolutePath, "-l", outFile.absolutePath)
                        standardOutput = stdout
                    }
                    val minAlign = stdout.toString().lines()
                        .filter { it.trim().startsWith("LOAD") }
                        .mapNotNull { line ->
                            line.trim().split(Regex("\\s+")).lastOrNull()
                                ?.removePrefix("0x")?.toLongOrNull(16)
                        }
                        .minOrNull()

                    if (minAlign != null && minAlign < requiredAlignment) {
                        misalignedNow += libName
                    }
                }
        }

        val newlyMisaligned = misalignedNow - knownMisaligned
        val noLongerMisaligned = knownMisaligned - misalignedNow
        val stillKnownMisaligned = misalignedNow intersect knownMisaligned

        if (stillKnownMisaligned.isNotEmpty()) {
            logger.warn("checkElfAlignment: known upstream gap, not yet fixable here: $stillKnownMisaligned")
        }
        if (noLongerMisaligned.isNotEmpty()) {
            logger.warn(
                "checkElfAlignment: $noLongerMisaligned are now 16 KB-aligned but still " +
                    "listed in this task's allowlist -- remove them from `knownMisaligned` " +
                    "in app/build.gradle.kts and update docs/handoff/PHASE-11.md."
            )
        }
        if (newlyMisaligned.isNotEmpty()) {
            throw GradleException(
                "checkElfAlignment: found native libraries below the 16 KB page-size " +
                    "alignment Android 15+ requires, and they're not in the known allowlist: " +
                    "$newlyMisaligned. See docs/PLAN.md's \"Module layout\" section."
            )
        }
        println("checkElfAlignment: OK (${stillKnownMisaligned.size} known allowlisted gap(s), 0 new)")
    }
}
